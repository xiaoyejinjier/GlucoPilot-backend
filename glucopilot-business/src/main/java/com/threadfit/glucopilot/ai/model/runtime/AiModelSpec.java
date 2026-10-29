package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.config.AiModelType;

import java.time.Duration;

/**
 * 一个模型在运行时需要的全部信息（不可变快照单元）。
 * <p>
 * 为什么要有这个类型：配置有三个来源（后台 DB / yaml / 代码默认），而"构造 ChatModel"这件事
 * 需要一个与来源无关的输入。把它定成不可变 record，是为了让"一次请求内看到的配置是同一份"这件事
 * 由类型保证——不能出现流到一半换了模型或换了密钥。
 *
 * @param modelCode     引用键（等于 {@code ai_model_config.model_code}）
 * @param providerCode  厂商：1 自研(DeepSeek) / 2,3,4 OpenAI 兼容 / 5 其他
 * @param apiKey        解密后的密钥；为空串表示"沿用 yaml/环境变量里的密钥"
 * @param type          用途：文本 / 视觉。决定"能不能被文本通道选为默认、被视觉场景绑定"
 * @param source        来源标签，"db" 或 "yaml"，用于日志与后台展示
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
        AiModelType type) {

    /** 兼容老调用点的默认值：没有声明用途的一律按文本处理（与 V45 回填口径一致）。 */
    public AiModelSpec {
        if (type == null) {
            type = AiModelType.TEXT;
        }
    }

    /** DeepSeek 自研 SDK 走这一支；其余一律按 OpenAI 兼容协议处理。 */
    public boolean deepSeek() {
        return providerCode == 1;
    }

    /** 是否为视觉（多模态）模型。 */
    public boolean vision() {
        return type.isVision();
    }

    /** 密钥是否由本配置自带（而不是沿用 yaml/环境变量）。 */
    public boolean hasOwnKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 构建指纹：只包含"会影响 client 构造"的字段（不含 modelCode/displayName/source）。
     * 用于判断"配置变了吗、缓存的 ChatModel 还能不能用"，同时**避免把密钥明文写进日志或 map key**。
     * <p>
     * 用途（type）也进指纹：它决定这份规格走哪条兜底凭据（见 AdminAiModelServiceImpl），
     * 类型变了就该重建实例，不能继续复用同一个缓存条目。
     */
    public String buildFingerprint() {
        return type + "|" + providerCode + "|" + nz(baseUrl) + "|" + nz(modelEndpoint) + "|"
                + temperature + "|" + topP + "|" + maxTokens + "|" + thinking + "|"
                + (timeout == null ? "" : timeout.toMillis()) + "|" + (apiKey == null ? "" : apiKey.hashCode());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
