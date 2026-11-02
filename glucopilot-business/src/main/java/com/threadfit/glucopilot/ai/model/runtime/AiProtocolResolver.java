package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 决定一次模型调用该用哪种协议。
 * <p>
 * 规则只有三条，优先级从高到低：
 * <ol>
 *   <li><b>视觉模型永远走 OpenAI 兼容协议</b>：视觉通道的模型（百炼 qwen-vl）本来就只有这一条路，
 *       和 {@code spec.vision()} 一致，与开关无关；</li>
 *   <li>文本模型由 {@code ai.routing.mode} 决定：{@code openai} 走 OpenAI 兼容，
 *       其它值（含缺省）走 DeepSeek 专用实现——迁移期默认仍是后者，保证上线前后行为一致；</li>
 *   <li>{@code spec == null}（理论上不该出现）按 OpenAI 兼容处理：它是更通用的一条路。</li>
 * </ol>
 * <p>
 * <b>为什么要有这个类，而不是在调用处各写一遍</b>：协议同时决定「造哪个 ChatModel」
 * （{@link ChatModelFactory}）和「造哪种 options」（{@code SceneModelResolver}）。
 * 这两处必须用<b>同一个判据</b>，否则会出现「用 OpenAI 客户端发 DeepSeek options」——
 * {@code OpenAiChatModel} 里是 {@code checkcast OpenAiChatOptions}，直接抛
 * {@code ClassCastException}，属于请求期硬失败。收敛到一个类，就不可能两边不一致。
 */
@Component
@RequiredArgsConstructor
public class AiProtocolResolver {

    /** {@code ai.routing.mode=openai}：文本也走 OpenAI 兼容协议。 */
    public static final String MODE_OPENAI = "openai";

    private final AIProperties aiProperties;

    /** 该规格实际会用哪种协议发请求。 */
    public AiProtocol protocolFor(AiModelSpec spec) {
        if (spec == null) {
            return AiProtocol.OPENAI_COMPAT;
        }
        if (spec.vision()) {
            return AiProtocol.OPENAI_COMPAT;
        }
        return aiProperties.getRouting().legacyDeepSeek() ? AiProtocol.DEEPSEEK : AiProtocol.OPENAI_COMPAT;
    }

    /** 是否仍在使用 DeepSeek 专用实现（启动日志用，方便一眼看出当前协议）。 */
    public boolean legacyDeepSeekText() {
        return aiProperties.getRouting().legacyDeepSeek();
    }
}
