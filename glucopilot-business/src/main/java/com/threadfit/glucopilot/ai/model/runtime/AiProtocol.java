package com.threadfit.glucopilot.ai.model.runtime;

/**
 * 与厂商通信的协议。
 * <p>
 * 引入这个枚举的唯一背景：DeepSeek 官方 API 本身是 OpenAI 兼容的（{@code POST /chat/completions}），
 * 我们却一直用 Spring AI 的 DeepSeek 专用实现（{@code DeepSeekApi} + {@code DeepSeekChatModel}），
 * 于是同一件事有两套类、两套 options、两套连接参数位置，而 {@code ChatModelFactory} 只能靠
 * {@code providerCode == 1} 猜该走哪一套。
 * <p>
 * <b>它和「通道」「用途」不是一个维度</b>：
 * <ul>
 *   <li>{@link AiProtocol}：用什么协议发请求（决定 options 的类型）；</li>
 *   <li>通道（{@code ai.channels.*}）：连哪个地址、用哪把密钥；</li>
 *   <li>{@code capability}（DB）：这个模型能不能看图、能不能调工具；</li>
 *   <li>{@code SceneCode}：业务场景，决定提示词与默认参数。</li>
 * </ul>
 * <b>迁移期存在</b>：协议统一完成后（DeepSeek 专用分支删除），本枚举只会有
 * {@link #OPENAI_COMPAT} 一个取值，届时连同 {@link AiProtocolResolver} 一起删除。
 */
public enum AiProtocol {

    /** Spring AI 的 DeepSeek 专用实现。迁移期保留，用于出问题时回滚。 */
    DEEPSEEK,

    /** OpenAI 兼容协议（DeepSeek 官方端点、阿里云百炼、任何 OpenAI 兼容网关都走这条）。 */
    OPENAI_COMPAT
}
