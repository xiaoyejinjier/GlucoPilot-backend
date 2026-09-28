package com.threadfit.glucopilot.ai.core;

import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.config.AIProperties.SceneProperties;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 场景 → 模型编码 / 请求级参数的解析器。
 * <p>
 * 取代了早期的 {@code AiModelInfo}：模型编码不再是「全局一个」，而是随场景解析，
 * 落库（{@code chat_message.model_code}）与回显都走这里，保证「落库的模型 = 实际调用的模型」。
 * <p>
 * 当前 provider 只有 DeepSeek，因此这里直接构造 {@link DeepSeekChatOptions}；
 * 这是本模块内**唯一**允许出现 DeepSeek 类型的地方，接入第二个 provider 时只改这里。
 */
@Component
public class SceneModelResolver {

    private final AIProperties aiProperties;

    /** 全局默认模型编码，与 {@code spring.ai.deepseek.chat.model} 保持单一来源。 */
    private final String defaultModelCode;

    public SceneModelResolver(AIProperties aiProperties,
                              @Value("${spring.ai.deepseek.chat.model:unknown}") String defaultModelCode) {
        this.aiProperties = aiProperties;
        this.defaultModelCode = defaultModelCode;
    }

    public String modelCode(SceneCode scene) {
        String configured = aiProperties.scene(scene).getModelCode();
        return StringUtils.hasText(configured) ? configured : defaultModelCode;
    }

    /** 场景是否声明了请求级参数覆盖。没声明就不往请求里塞 options，完全用 provider 全局配置。 */
    public boolean hasOverrides(SceneCode scene) {
        SceneProperties config = aiProperties.scene(scene);
        return StringUtils.hasText(config.getModelCode())
                || config.getTemperature() != null
                || config.getMaxTokens() != null
                || config.getThinking() != null;
    }

    /**
     * 构建请求级参数（返回 Builder：{@code ChatClient.prompt().options(...)} 接收的就是 Builder）。
     * <p>
     * 只写场景显式声明的字段，未声明的留空，由 Spring AI 与 {@code spring.ai.deepseek.chat.*}
     * 的默认值合并，避免同一份参数在两处维护。
     */
    public ChatOptions.Builder<?> optionsBuilder(SceneCode scene) {
        SceneProperties config = aiProperties.scene(scene);
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();
        if (StringUtils.hasText(config.getModelCode())) {
            builder.model(config.getModelCode());
        }
        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getThinking() != null) {
            if (config.getThinking()) {
                builder.enableThinking();
            } else {
                builder.disableThinking();
            }
        }
        return builder;
    }
}
