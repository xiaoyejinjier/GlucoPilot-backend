package com.threadfit.glucopilot.ai.config;

/**
 * 模型通道：一个通道 = 一套「协议 + 连接参数 + 凭据」。
 *
 * <h2>为什么要有这个概念</h2>
 * 本项目同时接两种协议：
 * <ul>
 *   <li>{@link #TEXT}：DeepSeek 自研协议（{@code DeepSeekApi}/{@code DeepSeekChatModel}），
 *       yaml 由 starter 的 {@code spring.ai.deepseek.*} 绑定；</li>
 *   <li>{@link #VISION}：OpenAI 兼容协议（百炼 qwen-vl），yaml 由本项目的 {@code ai.vision.*} 绑定。</li>
 * </ul>
 * 这两个协议在 Spring AI 里<b>没有共同父类</b>（{@code DeepSeekChatOptions} 不是
 * {@code OpenAiChatOptions} 的子类，2.0.1/2.0.2/2.1.0-RC1 都一样），连接参数的位置也不同：
 * OpenAI 把 {@code baseUrl/apiKey/timeout} 放进 options，DeepSeek 放在 {@code DeepSeekApi.Builder} 上。
 * <p>
 * 所以「统一读取」不能靠统一 options 类型，只能靠这一层：**通道决定连接，模型用途决定可选性，
 * 场景决定参数**。三者正交，任何一处判断都先回答「我判的是哪一类」。
 *
 * <p><b>通道 ≠ 模型用途</b>：前者是「用哪套协议调」，后者是「这个模型能不能看图」。
 * 后台 {@code ai_model_config.model_type} 表达的是后者，不要和本枚举混用。
 */
public enum AiChannel {

    /** DeepSeek 协议通道。 */
    TEXT("text"),

    /** OpenAI 兼容协议通道（视觉、ASR 等旁路都走它）。 */
    VISION("vision");

    private final String configKey;

    AiChannel(String configKey) {
        this.configKey = configKey;
    }

    /** yaml 里的键名：{@code ai.channels.<configKey>.*}。 */
    public String getConfigKey() {
        return configKey;
    }

    /** 按厂商编码推断通道。刻意只做「DeepSeek = 文本」这一条判断，其余一律 OpenAI 兼容。 */
    public static AiChannel ofProviderCode(Integer providerCode) {
        return providerCode != null && providerCode == 1 ? TEXT : VISION;
    }

    public static AiChannel ofConfigKey(String key) {
        for (AiChannel channel : values()) {
            if (channel.configKey.equalsIgnoreCase(key)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("未知的模型通道：" + key + "（可用：text / vision）");
    }
}
