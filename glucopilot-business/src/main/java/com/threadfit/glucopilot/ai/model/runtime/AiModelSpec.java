package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.config.AiModelCapability;

import java.time.Duration;

/**
 * 一个模型在运行时需要的全部信息（不可变快照单元）。
 * <p>
 * 为什么要有这个类型：配置有三个来源（后台 DB / yaml / 代码默认），而"构造 ChatModel"这件事
 * 需要一个与来源无关的输入。把它定成不可变 record，是为了让"一次请求内看到的配置是同一份"这件事
 * 由类型保证——不能出现流到一半换了模型或换了密钥。
 *
 * @param modelCode   引用键（等于 {@code ai_model_config.model_code}）
 * @param providerCode 厂商：1 自研(DeepSeek) / 2,3,4 OpenAI 兼容 / 5 其他
 * @param apiKey      解密后的密钥；为空串表示"沿用 yaml/环境变量里的密钥"
 * @param capability  能力集合（文本 / 视觉 / 工具 / 流式），决定能不能被某类场景选中与绑定
 * @param source      来源标签，"db" 或 "yaml"，用于日志与后台展示
 */
public record AiModelSpec(
        String modelCode,
        String displayName,
        int providerCode,
        String baseUrl,
        String apiKey,
        String modelEndpoint,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Boolean thinking,
        Duration timeout,
        String source,
        AiModelCapability capability) {

    /** 兼容老调用点的默认值：没有声明能力的一律按文本处理（与 V45/V46 回填口径一致）。 */
    public AiModelSpec {
        if (capability == null) {
            capability = AiModelCapability.text();
        }
    }

    /** DeepSeek 自研 SDK 走这一支；其余一律按 OpenAI 兼容协议处理。 */
    public boolean deepSeek() {
        return providerCode == 1;
    }

    /** 是否为视觉（多模态）模型——决定它落在哪个默认池。 */
    public boolean vision() {
        return capability.vision();
    }

    /** 能力集合是否满足某个场景的要求。 */
    public boolean supports(AiModelCapability required) {
        return capability.matches(required);
    }

    /** 密钥是否由本配置自带（而不是沿用 yaml/环境变量）。 */
    public boolean hasOwnKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 构建指纹：只包含"会影响 client 构造"的字段（不含 modelCode/displayName/source）。
     * 用于判断"配置变了吗、缓存的 ChatModel 还能不能用"，同时**避免把密钥明文写进日志或 map key**。
     * <p>
     * 能力集合也进指纹：它决定这份规格走哪条兜底凭据（见 AdminAiModelServiceImpl），
     * 能力变了就该重建实例，不能继续复用同一个缓存条目。
     */
    public String buildFingerprint() {
        return capability.value() + "|" + providerCode + "|" + nz(baseUrl) + "|" + nz(modelEndpoint) + "|"
                + temperature + "|" + topP + "|" + maxTokens + "|" + thinking + "|"
                + (timeout == null ? "" : timeout.toMillis()) + "|" + (apiKey == null ? "" : apiKey.hashCode());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
