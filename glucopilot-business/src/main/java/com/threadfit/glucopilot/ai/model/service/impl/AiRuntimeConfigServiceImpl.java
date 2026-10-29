package com.threadfit.glucopilot.ai.model.service.impl;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AiChannel;
import com.threadfit.glucopilot.ai.config.AiChannelProperties;
import com.threadfit.glucopilot.ai.config.AiModelType;
import com.threadfit.glucopilot.ai.core.SceneCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.threadfit.glucopilot.ai.model.entity.AiModelConfigEntity;
import com.threadfit.glucopilot.ai.model.mapper.AiModelConfigMapper;
import com.threadfit.glucopilot.ai.model.runtime.AiModelCipher;
import com.threadfit.glucopilot.ai.model.runtime.AiModelSpec;
import com.threadfit.glucopilot.ai.model.service.AiRuntimeConfigService;

/**
 * 运行时模型配置的唯一入口：把"后台 DB / yaml / 代码默认"三层合成一份不可变快照。
 *
 * <h2>三层与优先级</h2>
 * <ol>
 *   <li><b>DB（{@code ai_model_config}）</b>：启用且能解析的行，按 {@code model_code} 生效；</li>
 *   <li><b>yaml / 环境变量</b>：兜底，同时也是"DB 里没有这条配置"时的全部信息；</li>
 *   <li><b>代码默认</b>：即 yaml 里也没写时的空值，最终由 provider SDK 自己的默认兜住。</li>
 * </ol>
 *
 * <h2>几条刻意定死的行为</h2>
 * <ul>
 *   <li><b>密钥留空 = 沿用 yaml</b>：后台只改模型名/参数而不填密钥时，仍然用环境变量里的凭据。
 *       这条让 V13 种子的两行"开箱即用"，也让后台不必成为密钥的唯一来源。</li>
 *   <li><b>密钥解不开 = 整行跳过 + WARN</b>：拿一个解不开的密钥去发请求，等于带着未知凭证调用第三方，
 *       比"这条配置不生效"危险得多。跳过之后该 model_code 回落到 yaml。</li>
 *   <li><b>DB 异常 = 整次加载失败 = 沿用上一份快照</b>：后台误配或库抖动绝不能让 AI 全挂。</li>
 *   <li><b>60 秒 TTL 兜底刷新</b>：本实例改配置走 {@link #refreshNow()} 立即生效；其他实例靠 TTL
 *       最终一致（多实例的 Redis 广播是 P2 的优化项，不是正确性依赖）。</li>
 *   <li><b>文本通道默认 = <u>DB 里被启用且标为默认的那一行</u></b>，而不是"yaml 里那个模型名对应的行"。
 *       判据固定为：默认标记 → 唯一启用 → 最近修改 → yaml（见 {@link #pickDefault}）；视觉通道同构。
 *       <b>这条是 2026-10-13 一个线上反馈逼出来的</b>：原先通道默认写成
 *       {@code resolveText(yamlText.modelCode())}，于是"停用 flash、启用 pro"之后，
 *       {@code byCode} 里 flash 的 DB 条目消失、yaml 兜底条目又恰好叫 flash，解析结果仍是 flash
 *       ——后台改了什么，实际调的还是老模型，且不报任何错。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiRuntimeConfigServiceImpl implements AiRuntimeConfigService {

    /** 其他实例的最终一致窗口：超过这个时长再访问就重新加载一次。 */
    private static final Duration REFRESH_TTL = Duration.ofSeconds(60);

    /** 默认值的四种判据。文案会进日志与后台接口，改动要同步前端与文档。 */
    static final String DEFAULT_FLAG = "默认标记";
    static final String DEFAULT_ONLY_ENABLED = "唯一启用的文本模型";
    static final String DEFAULT_RECENT = "最近修改的文本模型";
    static final String DEFAULT_YAML = "yaml 兜底";

    /** 视觉通道的两条类型化判据（默认标记与 yaml 兜底与文本共用）。 */
    static final String VISION_ONLY_ENABLED = "唯一启用的视觉模型";
    static final String VISION_RECENT = "最近修改的视觉模型";

    private final AiModelConfigMapper mapper;
    private final AiModelCipher cipher;
    private final AIProperties aiProperties;

    /**
     * 两个通道的统一配置视图（{@code ai.channels.*}：文本引用 {@code spring.ai.deepseek.*}、
     * 视觉引用 {@code ai.vision.*}）。
     * <p>
     * 用它替代原先的 5 个 {@code @Value}：那 5 个只能表达文本通道，视觉只能从
     * {@link AIProperties} 另取，于是同一个「yaml 规格」的构造写了两份、字段各漏一半。
     */
    private final AiChannelProperties channels;

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final Object loadLock = new Object();
    private volatile long loadedAtNanos;
    /** 上一次加载的"内容指纹"：内容没变就不递增版本号，避免 TTL 刷新把模型缓存清掉。 */
    private volatile String loadedFingerprint;
    private long versionCounter;

    public AiRuntimeConfigServiceImpl(AiModelConfigMapper mapper,
                                  AiModelCipher cipher,
                                  AIProperties aiProperties,
                                  AiChannelProperties channels) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.aiProperties = aiProperties;
        this.channels = channels;
    }

    // ------------------------------------------------------------------ 对外

    /** 当前快照（按需刷新，带 TTL 与并发保护）。 */
    public Snapshot snapshot() {
        Snapshot existing = current.get();
        if (existing == null || isStale()) {
            synchronized (loadLock) {
                if (current.get() == null || isStale()) {
                    load();
                }
            }
        }
        return current.get();
    }

    /** 后台保存后调用：立即生效（当前实例），不等 TTL。 */
    public void refreshNow() {
        synchronized (loadLock) {
            load();
        }
    }

    /** 文本场景实际应使用的模型（场景绑定 → DB 默认 → yaml）。 */
    public AiModelSpec textSpec(SceneCode scene) {
        Snapshot snapshot = snapshot();
        if (scene.isRequiresVision()) {
            return snapshot.resolveVisionDefault();
        }
        String bound = aiProperties.scene(scene).getModelCode();
        if (StringUtils.hasText(bound)) {
            AiModelSpec spec = snapshot.byCode().get(bound);
            if (spec != null) {
                return spec;
            }
            // 场景绑定了一个没登记的编码：只能回到通道默认。刻意不返回"改了模型名的 yaml 规格"，
            // 否则会拿默认模型的凭据去调场景指定的模型（用 A 的 key 调 B）。
            log.warn("场景 {} 绑定的模型编码 {} 未在运行时配置中登记，本次使用通道默认模型 {}",
                    scene.getCode(), bound, snapshot.resolveTextDefault().modelCode());
        }
        return snapshot.resolveTextDefault();
    }

    /** 视觉场景（阶段 1）应使用的模型。 */
    public AiModelSpec visionSpec() {
        return snapshot().resolveVisionDefault();
    }

    /**
     * 按"模型编码"解析规格（场景绑定 {@code ai.scenes.<scene>.model-code} 走这里）。
     * <p>
     * 返回 null 表示这个编码既不是启用中的 DB 配置、也不是 yaml 里的那个模型名。
     */
    public AiModelSpec specByModelCode(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            return null;
        }
        return snapshot().byCode().get(modelCode);
    }

    /**
     * 按"请求里声明的模型名"解析规格：一次请求的 options 里可能覆盖了 model，
     * 此时必须用那个模型对应的凭据与端点，否则会出现"用 A 的 key 调 B 的模型"。
     * 找不到时返回 null，由调用方回落到通道默认。
     */
    public AiModelSpec specByModelName(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return null;
        }
        return snapshot().resolveByName(modelName);
    }

    // ------------------------------------------------------------------ 加载

    private boolean isStale() {
        return System.nanoTime() - loadedAtNanos > REFRESH_TTL.toNanos();
    }

    private void load() {
        Snapshot previous = current.get();
        try {
            AiModelSpec yamlText = yamlSpec(AiChannel.TEXT);
            AiModelSpec yamlVision = yamlSpec(AiChannel.VISION);

            Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
            Map<String, AiModelSpec> byName = new HashMap<>();
            List<Candidate> candidates = new ArrayList<>();
            int skipped = 0;
            for (AiModelConfigEntity row : mapper.selectEnabled()) {
                AiModelSpec spec = toSpec(row);
                if (spec == null) {
                    skipped++;
                    continue;
                }
                byCode.put(spec.modelCode(), spec);
                byName.put(spec.modelEndpoint(), spec);
                candidates.add(new Candidate(spec, isYes(row.getIsDefault()), row.getGmtModified(), row.getId(),
                        spec.type()));
            }
            // yaml 侧也进索引，但优先级低于 DB：同名时以 DB 为准（这正是"后台能覆盖 yaml"的落点）
            byCode.putIfAbsent(yamlText.modelCode(), yamlText);
            byName.putIfAbsent(yamlText.modelEndpoint(), yamlText);
            // 视觉的 yaml 规格同样进索引：不进的话 specByModelCode("视觉编码") 恒为 null，
            // 于是「按编码引用」的一切（场景绑定、后台回填 model_code）对视觉通道全是空转。
            // 视觉与文本在 byCode 里可能同名，putIfAbsent 保证 DB 优先、文本优先于视觉兜底。
            byCode.putIfAbsent(yamlVision.modelCode(), yamlVision);
            byName.putIfAbsent(yamlVision.modelEndpoint(), yamlVision);

            // 文本与视觉各判一次默认：两者按 model_type 分池，互不干扰
            DefaultPick textPick = pickDefault(candidates, yamlText, AiModelType.TEXT);
            DefaultPick visionPick = pickDefault(candidates, yamlVision, AiModelType.VISION);

            // 版本号按"内容"而不是时间：TTL 到期但配置没变时不换版本，模型缓存因此不会被无谓清空
            // （否则每 60 秒重建一次 HTTP 客户端，白白抖动连接池）
            String fingerprint = fingerprintOf(byCode, yamlVision);
            if (!fingerprint.equals(loadedFingerprint)) {
                loadedFingerprint = fingerprint;
                versionCounter++;
            }
            long version = versionCounter;
            Snapshot snapshot = new Snapshot(version, byCode, byName, yamlText, yamlVision,
                    textPick.spec(), textPick.reason(), visionPick.spec(), visionPick.reason(), skipped);
            current.set(snapshot);
            loadedAtNanos = System.nanoTime();

            if (previous == null || previous.version() != version) {
                AiModelSpec textDefault = snapshot.resolveTextDefault();
                AiModelSpec visionDefault = snapshot.resolveVisionDefault();
                log.info("AI 运行时配置已加载：DB 生效 {} 个模型（跳过 {}），文本默认={}[{}]（判据：{}），"
                                + "视觉默认={}[{}]（判据：{}）",
                        snapshot.dbModelCount(), skipped,
                        textDefault.modelEndpoint(), textDefault.source(), snapshot.textDefaultReason(),
                        visionDefault.modelEndpoint(), visionDefault.source(), snapshot.visionDefaultReason());
                if (DEFAULT_RECENT.equals(textPick.reason())) {
                    log.warn("文本通道没有「已启用且标为默认」的模型，已按最近修改时间选中 {}；"
                            + "请在后台把它显式设为默认，否则下次改动别的模型会让默认模型漂移",
                            textDefault.modelCode());
                } else if (DEFAULT_ONLY_ENABLED.equals(textPick.reason())) {
                    log.warn("文本通道没有默认模型标记，已使用当前唯一启用的文本模型 {}；建议在后台把它设为默认",
                            textDefault.modelCode());
                }
                if (VISION_RECENT.equals(visionPick.reason())) {
                    log.warn("视觉通道没有「已启用且标为默认」的视觉模型，已按最近修改时间选中 {}；"
                            + "请在后台把它显式设为默认", visionDefault.modelCode());
                } else if (VISION_ONLY_ENABLED.equals(visionPick.reason())) {
                    log.warn("视觉通道没有默认模型标记，已使用当前唯一启用的视觉模型 {}；建议在后台把它设为默认",
                            visionDefault.modelCode());
                }
            }
        } catch (RuntimeException e) {
            // 关键：加载失败不改动 current，沿用上一份可用快照（后台误配/库抖动不能让 AI 全挂）
            if (previous == null) {
                log.error("AI 运行时配置首次加载失败，将完全按 yaml 运行", e);
                AiModelSpec yamlText = yamlSpec(AiChannel.TEXT);
                AiModelSpec yamlVision = yamlSpec(AiChannel.VISION);
                Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
                byCode.put(yamlText.modelCode(), yamlText);
                byCode.putIfAbsent(yamlVision.modelCode(), yamlVision);
                Map<String, AiModelSpec> byName = new HashMap<>();
                byName.put(yamlText.modelEndpoint(), yamlText);
                byName.putIfAbsent(yamlVision.modelEndpoint(), yamlVision);
                current.set(new Snapshot(System.nanoTime(), byCode, byName, yamlText, yamlVision,
                        yamlText, DEFAULT_YAML, null, DEFAULT_YAML, 0));
                loadedAtNanos = System.nanoTime();
            } else {
                log.error("AI 运行时配置加载失败，继续沿用上一份快照（版本 {}）", previous.version(), e);
                loadedAtNanos = System.nanoTime();
            }
        }
    }

    /**
     * 某个通道"用哪个模型"的判定，顺序固定、每步都有名字。
     *
     * <ol>
     *   <li><b>默认标记</b>：启用且 {@code is_default=1} 的<b>同类型</b>行——管理员的显式意图，最高优先级；</li>
     *   <li><b>唯一启用</b>：只剩一个启用的同类型候选时直接用它（典型场景：停用旧的、启用新的）；</li>
     *   <li><b>最近修改</b>：多个候选且都没标默认时，取 {@code gmt_modified} 最新的那行。
     *       刻意<b>不</b>回落到 yaml：DB 里明明启用着配置却去用 yaml，正是"后台改了不生效"；</li>
     *   <li><b>yaml 兜底</b>：DB 里一个可用的同类型候选都没有时，才用 yaml/环境变量那套。</li>
     * </ol>
     *
     * <p>候选按 {@link AiModelSpec#type()} 过滤：类型来自 V45 的 {@code model_type}。
     * 之前用「endpoint 是否等于 yaml 视觉模型」猜视觉，漏掉后台新增的视觉模型，
     * 让它在文本通道里抢默认位（对话直接 404）。
     */
    private DefaultPick pickDefault(List<Candidate> candidates, AiModelSpec fallback, AiModelType type) {
        List<Candidate> sameType = candidates.stream().filter(c -> c.type() == type).toList();
        for (Candidate candidate : sameType) {
            if (candidate.isDefault()) {
                return new DefaultPick(candidate.spec(), DEFAULT_FLAG);
            }
        }
        boolean vision = type == AiModelType.VISION;
        if (sameType.size() == 1) {
            return new DefaultPick(sameType.get(0).spec(), vision ? VISION_ONLY_ENABLED : DEFAULT_ONLY_ENABLED);
        }
        if (!sameType.isEmpty()) {
            Candidate recent = sameType.stream()
                    .max(Comparator.comparing(Candidate::modifiedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Candidate::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElseThrow();
            return new DefaultPick(recent.spec(), vision ? VISION_RECENT : DEFAULT_RECENT);
        }
        return new DefaultPick(fallback, DEFAULT_YAML);
    }

    private static boolean isYes(Integer flag) {
        return flag != null && flag == 1;
    }

    /** 一条启用配置的候选信息：默认判定需要它，但不需要把整行带进快照。 */
    private record Candidate(AiModelSpec spec, boolean isDefault, LocalDateTime modifiedAt, Long id,
                             AiModelType type) {
    }

    /** 默认值判定结果：规格 + 判据（判据必须能一路走到日志和后台列表）。 */
    private record DefaultPick(AiModelSpec spec, String reason) {
    }

    /**
     * 内容指纹：把所有规格的构建指纹按 modelCode 排序拼接。
     * <p>
     * 刻意不含密钥明文（{@link AiModelSpec#buildFingerprint()} 里用的是 hashCode），也不含时间戳——
     * 它只回答一个问题："这次加载出来的可用配置，和上次是不是同一套"。
     */
    private static String fingerprintOf(Map<String, AiModelSpec> byCode, AiModelSpec yamlVision) {
        StringBuilder sb = new StringBuilder();
        byCode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(entry.getKey()).append('=')
                        .append(entry.getValue().buildFingerprint()).append(';'));
        sb.append("vision=").append(yamlVision.buildFingerprint());
        return sb.toString();
    }

    // ------------------------------------------------------------------ 转换

    /** DB 行 → 规格；密钥解不开返回 null（由调用方计为"跳过"）。 */
    private AiModelSpec toSpec(AiModelConfigEntity row) {
        AiModelType type = AiModelType.of(row.getModelType());
        String apiKey = "";
        if (StringUtils.hasText(row.getApiKeyCipher())) {
            String decrypted = cipher.decryptOrNull(row.getApiKeyCipher());
            if (decrypted == null) {
                log.warn("模型 {} 的密钥无法解密（检查 AI_CONFIG_MASTER_KEY 是否与加密时一致），"
                        + "本行被跳过并回落到 yaml", row.getModelCode());
                return null;
            }
            apiKey = decrypted;
        } else {
            // 留空 = 沿用 yaml/环境变量里的密钥：让"只改模型名"不必重新录一遍密钥
            apiKey = yamlKeyFor(type);
        }
        return new AiModelSpec(
                row.getModelCode(),
                row.getModelName(),
                row.getProviderCode() == null ? 1 : row.getProviderCode(),
                StringUtils.hasText(row.getApiBaseUrl()) ? row.getApiBaseUrl() : defaultBaseUrl(type),
                apiKey,
                row.getModelEndpoint(),
                toDouble(row.getTemperature()),
                toDouble(row.getTopP()),
                row.getMaxTokens(),
                row.getThinking(),
                Duration.ofMillis(row.getTimeoutMs() == null ? 60_000L : row.getTimeoutMs()),
                "db",
                type);
    }

    /**
     * 一个通道的 yaml 规格——两个通道共用这一份构造。
     * <p>
     * 先前是两个方法各写一遍，结果字段各漏一处：文本漏掉 timeout（写死 60 秒、不读 yaml），
     * 视觉漏掉 thinking。合并之后新增字段只需要改一处，也不会再出现「一个通道有、另一个没有」。
     */
    private AiModelSpec yamlSpec(AiChannel channel) {
        AiChannelProperties.Channel config = channels.channel(channel);
        boolean text = channel == AiChannel.TEXT;
        return new AiModelSpec(
                config.getModel(),
                text ? "DeepSeek 文本模型（yaml）" : "视觉模型（yaml）",
                text ? 1 : 2,
                config.getBaseUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getTemperature(),
                null,
                config.getMaxTokens(),
                null,
                config.timeout(),
                "yaml",
                text ? AiModelType.TEXT : AiModelType.VISION);
    }

    /**
     * 未自带密钥时，按<b>用途</b>取 yaml 里对应通道的密钥。
     * <p>
     * 批次 2 之前这里用 {@code provider_code} 推断通道，于是"厂商填 2（OpenAI 兼容）的文本模型"
     * 会拿到视觉通道的 key/base-url——串道了还查不出来（调用直接 401/404）。
     * 类型是显式字段，按它取凭据才是确定性的。
     */
    private String yamlKeyFor(AiModelType type) {
        return channels.channel(channelOf(type)).getApiKey();
    }

    private String defaultBaseUrl(AiModelType type) {
        return channels.channel(channelOf(type)).getBaseUrl();
    }

    /** 用途 → 通道：文本模型用文本通道凭据，视觉模型用视觉通道凭据。 */
    private static AiChannel channelOf(AiModelType type) {
        return type == AiModelType.VISION ? AiChannel.VISION : AiChannel.TEXT;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 不可变快照：一次请求内看到的配置必须是同一份。
     *
     * @param version           内容指纹版本：内容变了才变（TTL 刷新不换版本）
     * @param byCode            model_code → 规格（含 yaml 侧兜底项）
     * @param byName            model_endpoint → 规格，用于按请求里声明的模型名反查凭据
     * @param textDefault       文本通道默认规格，加载时定下（见 {@link #pickDefault}）
     * @param textDefaultReason 该默认值是怎么选出来的，进日志也进后台列表
     * @param visionDefault     视觉通道默认规格，加载时定下（见 {@link #pickDefault}）
     * @param visionDefaultReason 视觉默认值的判据
     * @param skipped           因密钥不可用被跳过的行数（观测用）
     */

}
