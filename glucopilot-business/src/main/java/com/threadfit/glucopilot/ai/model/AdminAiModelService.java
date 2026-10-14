package com.threadfit.glucopilot.ai.model;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 后台模型配置的业务逻辑（P0 只读 + P1 可写）。
 *
 * <h2>三条设计原则</h2>
 * <ol>
 *   <li><b>密钥只出不进的反向</b>：出参永远是掩码，入参"留空=不改"；
 *       加密与解密全在 {@link AiModelCipher}，本类不接触明文之外的任何形式；</li>
 *   <li><b>写完立刻生效</b>：任何变更成功后调用 {@link AiRuntimeConfigService#refreshNow()}，
 *       本实例的<b>下一条消息</b>就用新配置（其他实例靠 60 秒 TTL 最终一致，这是 P2 才做广播的原因）；</li>
 *   <li><b>失败的写入不改运行态</b>：校验/乐观锁/加密任何一步失败都直接抛业务异常，
 *       运行中的快照不受影响——后台点错了也不该影响正在服务的那份配置。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAiModelService {

    /** 连通性测试的超时上限：既是体验问题，也是防止后台点一下就把请求线程挂很久。 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Map<Integer, String> PROVIDER_TEXT = Map.of(
            1, "DeepSeek（自研 SDK）",
            2, "OpenAI 兼容",
            3, "通义千问",
            4, "文心一言",
            5, "其他");

    private final AiModelConfigMapper mapper;
    private final AiModelCipher cipher;
    private final AiRuntimeConfigService runtimeConfig;
    private final ChatModelFactory chatModelFactory;

    // ------------------------------------------------------------------ 查询

    public AdminAiModelDtos.ModelList list() {
        List<AdminAiModelDtos.ModelRow> rows = new ArrayList<>();
        for (AiModelConfigEntity entity : mapper.selectList(null)) {
            rows.add(toRow(entity));
        }
        AiRuntimeConfigService.Snapshot snapshot = runtimeConfig.snapshot();
        return AdminAiModelDtos.ModelList.builder()
                .records(rows)
                .textDefaultCode(snapshot.resolveTextDefault().modelCode())
                .textDefaultSource(snapshot.resolveTextDefault().source())
                .textDefaultReason(snapshot.textDefaultReason())
                .build();
    }

    public AdminAiModelDtos.ModelRow detail(Long id) {
        return toRow(require(id));
    }

    // ------------------------------------------------------------------ 写入

    @Transactional
    public AdminAiModelDtos.ModelRow create(AdminAiModelDtos.SaveRequest request) {
        if (!StringUtils.hasText(request.getModelCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型编码不能为空");
        }
        if (mapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiModelConfigEntity>()
                .eq(AiModelConfigEntity::getModelCode, request.getModelCode())) > 0) {
            // 唯一键冲突用 CONFLICT(40900)：管理端据此提示"已存在"而不是"业务校验失败"，
            // 不必再靠文案匹配（BIZ_REJECTED 40001 承载了太多语义）。
            throw new BizException(ErrorCode.CONFLICT, "模型编码已存在：" + request.getModelCode());
        }
        AiModelConfigEntity entity = new AiModelConfigEntity();
        entity.setModelCode(request.getModelCode().trim());
        applyRequest(entity, request);
        // 新增时密钥可留空（表示"沿用 yaml"），这与"修改时留空=不改"语义一致，不需要区分
        writeKey(entity, request);
        entity.setConfigVersion(1L);
        mapper.insert(entity);
        if (isYes(request.getIsDefault())) {
            mapper.clearOtherDefaults(entity.getId());
        }
        runtimeConfig.refreshNow();
        log.info("后台新增模型配置 modelCode={} provider={} endpoint={} 密钥来源={}",
                entity.getModelCode(), entity.getProviderCode(), entity.getModelEndpoint(), entity.getApiKeyCipher() == null ? "yaml" : "db");
        return toRow(require(entity.getId()));
    }

    @Transactional
    public AdminAiModelDtos.ModelRow update(Long id, AdminAiModelDtos.SaveRequest request) {
        if (request.getConfigVersion() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 configVersion（乐观锁），请刷新后重试");
        }
        AiModelConfigEntity existing = require(id);
        AiModelConfigEntity entity = new AiModelConfigEntity();
        entity.setId(id);
        // 模型编码是不可变引用键：被场景/代码引用后改名会让配置静默失效，因此忽略请求里的值
        entity.setModelCode(existing.getModelCode());
        applyRequest(entity, request);

        String cipherText = null;
        String cipherAlgo = null;
        Integer keyVersion = null;
        if (StringUtils.hasText(request.getApiKey())) {
            cipherText = cipher.encrypt(request.getApiKey().trim());
            cipherAlgo = AiModelCipher.ALGO_AES_GCM;
            keyVersion = cipher.currentKeyVersion();
        }

        int updated = mapper.updateWithVersion(entity, request.getConfigVersion().longValue(),
                cipherText, cipherAlgo, keyVersion);
        if (updated == 0) {
            // 乐观锁冲突给独立错误码 CONFLICT(40900)：之前与"模型编码已存在"等校验错误共用
            // BIZ_REJECTED(40001)，管理端只能按文案猜，前端也无法区分"该刷新重试"还是"该改输入"。
            throw new BizException(ErrorCode.CONFLICT, "配置已被他人修改，请刷新后重试");
        }
        if (Boolean.TRUE.equals(request.getClearApiKey())) {
            // 清空密钥是第二条带版本条件的语句；它影响的是"密钥被清掉"这个安全相关的效果，
            // 因此返回 0 行（版本对不上）必须显式失败，不能静默当成功。
            if (mapper.clearApiKey(id, request.getConfigVersion().longValue() + 1) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "清空密钥时配置版本已变化，请刷新后重试");
            }
        }
        if (isYes(request.getIsDefault())) {
            mapper.markDefault(id);
            mapper.clearOtherDefaults(id);
        }
        runtimeConfig.refreshNow();
        log.info("后台修改模型配置 id={} modelCode={} endpoint={} 密钥{}",
                id, entity.getModelCode(), entity.getModelEndpoint(),
                StringUtils.hasText(request.getApiKey()) ? "已更新" : (Boolean.TRUE.equals(request.getClearApiKey()) ? "已清空" : "未改动"));
        return toRow(require(id));
    }

    @Transactional
    public AdminAiModelDtos.ModelRow setEnabled(Long id, Integer enabled) {
        AiModelConfigEntity existing = require(id);
        mapper.updateEnabled(id, isYes(enabled) ? 1 : 0);
        if (!isYes(enabled) && isYes(existing.getIsDefault())) {
            // 停用默认模型时顺手摘掉它的默认标记：留着会得到"已停用却仍标着默认"的行，
            // 让人以为它还在生效（而运行时早就换人了）。至于之后谁接手，由快照的
            // "唯一启用 → 最近修改" 判据决定，日志里会写明选了谁、为什么。
            mapper.clearDefault(id);
            log.warn("停用的是当前默认模型 id={} modelCode={}，已摘掉它的默认标记；"
                    + "新默认由「唯一启用/最近修改」判据选出，详见紧随其后的运行时配置加载日志",
                    id, existing.getModelCode());
        }
        runtimeConfig.refreshNow();
        log.info("后台{}模型配置 id={}", isYes(enabled) ? "启用" : "停用", id);
        return toRow(require(id));
    }

    @Transactional
    public AdminAiModelDtos.ModelRow setDefault(Long id) {
        require(id);
        mapper.markDefault(id);
        mapper.clearOtherDefaults(id);
        runtimeConfig.refreshNow();
        log.info("后台把模型配置 id={} 设为默认", id);
        return toRow(require(id));
    }

    /**
     * 连通性测试：用这条配置发一次最小请求，把结果分类回写。
     * <p>
     * 测试<b>不</b>推进 {@code config_version}（{@link AiModelConfigMapper#updateTestResult} 里没碰它）：
     * 否则"点一次测试"就会被当成一次配置变更，把别人正在编辑的表单判成版本冲突。
     */
    public AdminAiModelDtos.TestResult test(Long id) {
        AiModelConfigEntity entity = require(id);
        AiModelSpec spec = toSpecForTest(entity);
        ChatModelFactory.ConnectivityResult result = chatModelFactory.test(spec, TEST_TIMEOUT);
        try {
            String summary = (result.advice() + " | " + result.message());
            mapper.updateTestResult(id, result.ok(),
                    summary.length() > 500 ? summary.substring(0, 500) : summary);
        } catch (RuntimeException e) {
            // 回写失败不影响本次测试结论：管理员已经能看到结果了
            log.warn("连通性测试结果回写失败 id={}", id, e);
        }
        return AdminAiModelDtos.TestResult.builder()
                .ok(result.ok())
                .category(result.category())
                .advice(result.advice())
                .elapsedMs(result.elapsedMs())
                .modelEcho(result.modelEcho())
                .message(result.message())
                .build();
    }

    // ------------------------------------------------------------------ 内部

    private AiModelConfigEntity require(Long id) {
        AiModelConfigEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
        return entity;
    }

    private void applyRequest(AiModelConfigEntity entity, AdminAiModelDtos.SaveRequest request) {
        entity.setModelName(request.getModelName().trim());
        entity.setProviderCode(request.getProviderCode());
        entity.setApiBaseUrl(blankToNull(request.getApiBaseUrl()));
        entity.setModelEndpoint(request.getModelEndpoint().trim());
        entity.setTemperature(request.getTemperature());
        entity.setTopP(request.getTopP());
        entity.setMaxTokens(request.getMaxTokens());
        entity.setThinking(request.getThinking());
        entity.setTimeoutMs(request.getTimeoutMs());
        entity.setIsEnabled(isYes(request.getIsEnabled()) ? 1 : 0);
        entity.setIsDefault(isYes(request.getIsDefault()) ? 1 : 0);
        entity.setRemark(blankToNull(request.getRemark()));
    }

    private void writeKey(AiModelConfigEntity entity, AdminAiModelDtos.SaveRequest request) {
        if (StringUtils.hasText(request.getApiKey())) {
            entity.setApiKeyCipher(cipher.encrypt(request.getApiKey().trim()));
            entity.setCipherAlgo(AiModelCipher.ALGO_AES_GCM);
            entity.setKeyVersion(cipher.currentKeyVersion());
        }
    }

    private AdminAiModelDtos.ModelRow toRow(AiModelConfigEntity entity) {
        int provider = entity.getProviderCode() == null ? 1 : entity.getProviderCode();
        AiRuntimeConfigService.Snapshot snapshot = runtimeConfig.snapshot();
        String decrypted = cipher.decryptOrNull(entity.getApiKeyCipher());
        AiModelSpec registered = snapshot.byCode().get(entity.getModelCode());
        String effectiveKey = StringUtils.hasText(decrypted)
                ? decrypted
                : (registered == null ? "" : registered.apiKey());
        String keySource;
        if (StringUtils.hasText(decrypted)) {
            keySource = "db";
        } else if (StringUtils.hasText(effectiveKey)) {
            keySource = "yaml";
        } else {
            keySource = "none";
        }
        return AdminAiModelDtos.ModelRow.builder()
                .id(String.valueOf(entity.getId()))
                .modelCode(entity.getModelCode())
                .modelName(entity.getModelName())
                .providerCode(provider)
                .providerText(PROVIDER_TEXT.getOrDefault(provider, "其他"))
                .apiBaseUrl(entity.getApiBaseUrl())
                .modelEndpoint(entity.getModelEndpoint())
                .temperature(entity.getTemperature())
                .topP(entity.getTopP())
                .maxTokens(entity.getMaxTokens())
                .thinking(entity.getThinking())
                .timeoutMs(entity.getTimeoutMs())
                .isDefault(entity.getIsDefault())
                .isEnabled(entity.getIsEnabled())
                .apiKeyMasked(AiModelCipher.mask(effectiveKey))
                .apiKeySource(keySource)
                .configVersion(entity.getConfigVersion() == null ? 1 : entity.getConfigVersion().intValue())
                .source(isYes(entity.getIsEnabled()) ? "db" : "yaml")
                .effectiveRole(effectiveRole(entity, snapshot))
                .lastTestAt(entity.getLastTestAt())
                .lastTestOk(entity.getLastTestOk())
                .lastTestMessage(entity.getLastTestMessage())
                .remark(entity.getRemark())
                .updatedAt(entity.getGmtModified())
                .build();
    }

    /**
     * 这一行当前是否真的在给某个通道供能——列表里那列"生效中"就是它。
     * <p>
     * 为什么要专门算一遍：{@code is_enabled}/{@code is_default} 是"意愿"，而这里回答的是"事实"。
     * 2026-10-13 的反馈（停用 flash、启用 pro，结果还是调 flash）之所以难自查，就是因为页面上
     * 每个开关看起来都对，唯独没有一列告诉你"现在真正在用的是谁"。
     */
    private static String effectiveRole(AiModelConfigEntity entity, AiRuntimeConfigService.Snapshot snapshot) {
        if (!isYes(entity.getIsEnabled())) {
            return null;
        }
        if (entity.getModelCode().equals(snapshot.resolveTextDefault().modelCode())) {
            return "text-default";
        }
        AiModelSpec vision = snapshot.resolveVision();
        if ("db".equals(vision.source()) && entity.getModelCode().equals(vision.modelCode())) {
            return "vision";
        }
        return null;
    }

    /**
     * 测试用的规格：<b>以本行自己的配置为准</b>（本行密钥优先，没有才沿用 yaml），
     * 这样"点测试 = 测这一行保存的凭据"，而不是测别人。
     * <p>
     * <b>2026-10-14 修正</b>：此前先做 {@code snapshot.resolveByName(endpoint)}，命中就直接返回那份运行时规格。
     * 运行时索引里同名条目可能是 <b>yaml 规格</b>（本行被停用时它不在 DB 索引里，只剩 yaml 兜底），
     * 于是"停用后点测试"会拿 yaml 的密钥去测——即使本行存的是无效密钥也会显示成功。
     * 这是 cipher-e2e 脚本在真实库上跑出来的（target 行被停用时 `/test` 返回 OK 而非 AUTH）。
     */
    private AiModelSpec toSpecForTest(AiModelConfigEntity entity) {
        AiRuntimeConfigService.Snapshot snapshot = runtimeConfig.snapshot();
        String decrypted = cipher.decryptOrNull(entity.getApiKeyCipher());
        String ownKey = StringUtils.hasText(decrypted) ? decrypted : null;
        int provider = entity.getProviderCode() == null ? 1 : entity.getProviderCode();
        String effectiveKey = ownKey != null
                ? ownKey
                : (provider == 1 ? snapshot.yamlText().apiKey() : snapshot.yamlVision().apiKey());
        return new AiModelSpec(
                entity.getModelCode(),
                entity.getModelName(),
                provider,
                StringUtils.hasText(entity.getApiBaseUrl()) ? entity.getApiBaseUrl()
                        : (provider == 1 ? snapshot.yamlText().baseUrl() : snapshot.yamlVision().baseUrl()),
                effectiveKey,
                entity.getModelEndpoint(),
                toDouble(entity.getTemperature()),
                toDouble(entity.getTopP()),
                entity.getMaxTokens(),
                entity.getThinking(),
                Duration.ofMillis(entity.getTimeoutMs() == null ? 60_000L : entity.getTimeoutMs()),
                "db");
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static boolean isYes(Integer flag) {
        return flag != null && flag == 1;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
