package com.threadfit.glucopilot.ai.model;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 *       判据固定为：默认标记 → 唯一启用 → 最近修改 → yaml（见 {@link #pickTextDefault}）。
 *       <b>这条是 2026-10-13 一个线上反馈逼出来的</b>：原先通道默认写成
 *       {@code resolveText(yamlText.modelCode())}，于是"停用 flash、启用 pro"之后，
 *       {@code byCode} 里 flash 的 DB 条目消失、yaml 兜底条目又恰好叫 flash，解析结果仍是 flash
 *       ——后台改了什么，实际调的还是老模型，且不报任何错。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiRuntimeConfigService {

    /** 其他实例的最终一致窗口：超过这个时长再访问就重新加载一次。 */
    private static final Duration REFRESH_TTL = Duration.ofSeconds(60);

    /** 文本默认值的四种判据。文案会进日志与后台接口，改动要同步前端与文档。 */
    static final String DEFAULT_FLAG = "默认标记";
    static final String DEFAULT_ONLY_ENABLED = "唯一启用的文本模型";
    static final String DEFAULT_RECENT = "最近修改的文本模型";
    static final String DEFAULT_YAML = "yaml 兜底";

    private final AiModelConfigMapper mapper;
    private final AiModelCipher cipher;
    private final AIProperties aiProperties;

    /** yaml/环境变量侧的文本模型（DeepSeek 自研 SDK 那支）。 */
    private final String yamlTextModel;
    private final String yamlTextApiKey;
    private final String yamlTextBaseUrl;
    private final Double yamlTextTemperature;
    private final Integer yamlTextMaxTokens;

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final Object loadLock = new Object();
    private volatile long loadedAtNanos;
    /** 上一次加载的"内容指纹"：内容没变就不递增版本号，避免 TTL 刷新把模型缓存清掉。 */
    private volatile String loadedFingerprint;
    private long versionCounter;

    public AiRuntimeConfigService(AiModelConfigMapper mapper,
                                  AiModelCipher cipher,
                                  AIProperties aiProperties,
                                  @Value("${spring.ai.deepseek.chat.model:}") String yamlTextModel,
                                  @Value("${spring.ai.deepseek.api-key:}") String yamlTextApiKey,
                                  @Value("${spring.ai.deepseek.base-url:}") String yamlTextBaseUrl,
                                  @Value("${spring.ai.deepseek.chat.temperature:}") Double yamlTextTemperature,
                                  @Value("${spring.ai.deepseek.chat.max-tokens:}") Integer yamlTextMaxTokens) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.aiProperties = aiProperties;
        this.yamlTextModel = yamlTextModel;
        this.yamlTextApiKey = yamlTextApiKey;
        this.yamlTextBaseUrl = yamlTextBaseUrl;
        this.yamlTextTemperature = yamlTextTemperature;
        this.yamlTextMaxTokens = yamlTextMaxTokens;
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
            return snapshot.resolveVision();
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
        return snapshot().resolveVision();
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
            AiModelSpec yamlText = yamlTextSpec();
            AiModelSpec yamlVision = yamlVisionSpec();

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
                        yamlVision.modelEndpoint().equals(spec.modelEndpoint())));
            }
            // yaml 侧也进索引，但优先级低于 DB：同名时以 DB 为准（这正是"后台能覆盖 yaml"的落点）
            byCode.putIfAbsent(yamlText.modelCode(), yamlText);
            byName.putIfAbsent(yamlText.modelEndpoint(), yamlText);

            DefaultPick pick = pickTextDefault(candidates, yamlText);

            // 版本号按"内容"而不是时间：TTL 到期但配置没变时不换版本，模型缓存因此不会被无谓清空
            // （否则每 60 秒重建一次 HTTP 客户端，白白抖动连接池）
            String fingerprint = fingerprintOf(byCode, yamlVision);
            if (!fingerprint.equals(loadedFingerprint)) {
                loadedFingerprint = fingerprint;
                versionCounter++;
            }
            long version = versionCounter;
            Snapshot snapshot = new Snapshot(version, byCode, byName, yamlText, yamlVision,
                    pick.spec(), pick.reason(), skipped);
            current.set(snapshot);
            loadedAtNanos = System.nanoTime();

            if (previous == null || previous.version() != version) {
                AiModelSpec textDefault = snapshot.resolveTextDefault();
                log.info("AI 运行时配置已加载：DB 生效 {} 个模型（跳过 {}），文本默认={}[{}]（判据：{}），视觉={}[{}]",
                        snapshot.dbModelCount(), skipped,
                        textDefault.modelEndpoint(), textDefault.source(), snapshot.textDefaultReason(),
                        snapshot.resolveVision().modelEndpoint(), snapshot.resolveVision().source());
                if (DEFAULT_RECENT.equals(pick.reason())) {
                    log.warn("文本通道没有「已启用且标为默认」的模型，已按最近修改时间选中 {}；"
                            + "请在后台把它显式设为默认，否则下次改动别的模型会让默认模型漂移",
                            textDefault.modelCode());
                } else if (DEFAULT_ONLY_ENABLED.equals(pick.reason())) {
                    log.warn("文本通道没有默认模型标记，已使用当前唯一启用的文本模型 {}；建议在后台把它设为默认",
                            textDefault.modelCode());
                }
            }
        } catch (RuntimeException e) {
            // 关键：加载失败不改动 current，沿用上一份可用快照（后台误配/库抖动不能让 AI 全挂）
            if (previous == null) {
                log.error("AI 运行时配置首次加载失败，将完全按 yaml 运行", e);
                AiModelSpec yamlText = yamlTextSpec();
                AiModelSpec yamlVision = yamlVisionSpec();
                Map<String, AiModelSpec> byCode = new LinkedHashMap<>();
                byCode.put(yamlText.modelCode(), yamlText);
                Map<String, AiModelSpec> byName = new HashMap<>();
                byName.put(yamlText.modelEndpoint(), yamlText);
                current.set(new Snapshot(System.nanoTime(), byCode, byName, yamlText, yamlVision,
                        yamlText, DEFAULT_YAML, 0));
                loadedAtNanos = System.nanoTime();
            } else {
                log.error("AI 运行时配置加载失败，继续沿用上一份快照（版本 {}）", previous.version(), e);
                loadedAtNanos = System.nanoTime();
            }
        }
    }

    /**
     * 文本通道"用哪个模型"的判定，顺序固定、每步都有名字。
     *
     * <ol>
     *   <li><b>默认标记</b>：启用且 {@code is_default=1} 的行——管理员的显式意图，最高优先级；</li>
     *   <li><b>唯一启用</b>：只剩一个启用的文本候选行时直接用它（典型场景：停用旧的、启用新的）；</li>
     *   <li><b>最近修改</b>：多个候选且都没标默认时，取 {@code gmt_modified} 最新的那行。
     *       刻意<b>不</b>回落到 yaml：DB 里明明启用着配置却去用 yaml，正是本次要修的那种"后台改了不生效"；</li>
     *   <li><b>yaml 兜底</b>：DB 里一个可用的文本候选都没有时，才用 yaml/环境变量那套。</li>
     * </ol>
     *
     * <p>视觉行（{@code model_endpoint} 等于 {@code ai.vision.model}）不参与文本候选：
     * 它对文本通道是"另一个 provider 的模型"，让它抢默认位只会在对话里报 404。
     */
    private DefaultPick pickTextDefault(List<Candidate> candidates, AiModelSpec yamlText) {
        List<Candidate> text = candidates.stream().filter(c -> !c.visionLike()).toList();
        for (Candidate candidate : text) {
            if (candidate.isDefault()) {
                return new DefaultPick(candidate.spec(), DEFAULT_FLAG);
            }
        }
        if (text.size() == 1) {
            return new DefaultPick(text.get(0).spec(), DEFAULT_ONLY_ENABLED);
        }
        if (!text.isEmpty()) {
            Candidate recent = text.stream()
                    .max(Comparator.comparing(Candidate::modifiedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Candidate::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElseThrow();
            return new DefaultPick(recent.spec(), DEFAULT_RECENT);
        }
        return new DefaultPick(yamlText, DEFAULT_YAML);
    }

    private static boolean isYes(Integer flag) {
        return flag != null && flag == 1;
    }

    /** 一条启用配置的候选信息：默认判定需要它，但不需要把整行带进快照。 */
    private record Candidate(AiModelSpec spec, boolean isDefault, LocalDateTime modifiedAt, Long id,
                             boolean visionLike) {
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
            apiKey = yamlKeyFor(row.getProviderCode());
        }
        return new AiModelSpec(
                row.getModelCode(),
                row.getModelName(),
                row.getProviderCode() == null ? 1 : row.getProviderCode(),
                StringUtils.hasText(row.getApiBaseUrl()) ? row.getApiBaseUrl() : defaultBaseUrl(row.getProviderCode()),
                apiKey,
                row.getModelEndpoint(),
                toDouble(row.getTemperature()),
                toDouble(row.getTopP()),
                row.getMaxTokens(),
                row.getThinking(),
                Duration.ofMillis(row.getTimeoutMs() == null ? 60_000L : row.getTimeoutMs()),
                "db");
    }

    private AiModelSpec yamlTextSpec() {
        return new AiModelSpec(
                yamlTextModel,
                "DeepSeek 文本模型（yaml）",
                1,
                yamlTextBaseUrl,
                yamlTextApiKey,
                yamlTextModel,
                yamlTextTemperature,
                null,
                yamlTextMaxTokens,
                null,
                Duration.ofSeconds(60),
                "yaml");
    }

    private AiModelSpec yamlVisionSpec() {
        AIProperties.VisionProperties vision = aiProperties.getVision();
        return new AiModelSpec(
                vision.getModel(),
                "视觉模型（yaml）",
                2,
                vision.getBaseUrl(),
                vision.getApiKey(),
                vision.getModel(),
                vision.getTemperature(),
                null,
                vision.getMaxTokens(),
                null,
                Duration.ofSeconds(vision.getTimeoutSeconds()),
                "yaml");
    }

    /** 未自带密钥时，按 provider 取 yaml 里的对应密钥。 */
    private String yamlKeyFor(Integer providerCode) {
        int provider = providerCode == null ? 1 : providerCode;
        return provider == 1 ? yamlTextApiKey : aiProperties.getVision().getApiKey();
    }

    private String defaultBaseUrl(Integer providerCode) {
        int provider = providerCode == null ? 1 : providerCode;
        if (provider == 1) {
            return yamlTextBaseUrl;
        }
        return aiProperties.getVision().getBaseUrl();
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
     * @param textDefault       文本通道默认规格，加载时定下（见 {@link #pickTextDefault}）
     * @param textDefaultReason 该默认值是怎么选出来的，进日志也进后台列表
     * @param skipped           因密钥不可用被跳过的行数（观测用）
     */
    public record Snapshot(long version,
                           Map<String, AiModelSpec> byCode,
                           Map<String, AiModelSpec> byName,
                           AiModelSpec yamlText,
                           AiModelSpec yamlVision,
                           AiModelSpec textDefault,
                           String textDefaultReason,
                           int skipped) {

        /**
         * 文本通道默认规格（后台默认行 → 唯一/最近启用的行 → yaml），<b>永不为 null</b>。
         * <p>
         * 这是文本通道唯一该用的解析入口：{@link #resolveText(String)} 只回答"某个编码对应哪份配置"，
         * 拿 yaml 的模型名去调用它，会在后台把那个模型停用后悄悄退回 yaml。
         */
        public AiModelSpec resolveTextDefault() {
            return textDefault == null ? yamlText : textDefault;
        }

        public String textDefaultReason() {
            return textDefaultReason == null ? DEFAULT_YAML : textDefaultReason;
        }

        /** 按模型编码解析（场景绑定等显式指定编码的场景）。code 为空或查不到时回落 yaml 文本规格。 */
        public AiModelSpec resolveText(String modelCode) {
            if (StringUtils.hasText(modelCode)) {
                AiModelSpec spec = byCode.get(modelCode);
                if (spec != null) {
                    return spec;
                }
                // 场景指向了一个既不在 DB、也不是 yaml 默认的模型：仍用 yaml 的凭据，
                // 但模型名由请求 options 决定（SceneModelResolver 会带上），因此不算错误配置。
                log.debug("模型编码 {} 未在运行时配置中登记，回落 yaml 文本规格", modelCode);
            }
            return yamlText;
        }

        /**
         * 视觉通道解析：视觉模型编码取自 {@code ai.vision.model}（yaml），DB 里同一 <b>endpoint</b> 的行优先。
         * <p>
         * 按 endpoint 而不是 model_code 反查：这两列可以不同名（后台允许"编码 qwen-vl、端点 qwen3-vl-plus"），
         * 早先按 model_code 查是撞上 V13 种子里两列恰好相等才没暴露。
         */
        public AiModelSpec resolveVision() {
            AiModelSpec spec = byName.get(yamlVision.modelEndpoint());
            return spec != null && "db".equals(spec.source()) ? spec : yamlVision;
        }

        public AiModelSpec resolveByName(String modelName) {
            return byName.get(modelName);
        }

        public int dbModelCount() {
            return (int) byCode.values().stream().filter(s -> "db".equals(s.source())).count();
        }
    }
}
