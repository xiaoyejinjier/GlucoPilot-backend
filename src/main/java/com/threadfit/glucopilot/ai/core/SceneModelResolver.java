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
 * <p>
 * <b>视觉场景（{@link SceneCode#isRequiresVision()}）是例外，三个方法都要区别对待：</b>
 * 它们的模型与参数属于<b>另一个 provider</b>，配置在 {@code ai.vision.*}，
 * 并且在 {@code AiVisionClientConfig} 构造 client 时就烤进了 defaultOptions。
 * 若这里不做区分，会得到三种错误的组合：
 * <ul>
 *   <li>{@link #modelCode} 回落成 {@code spring.ai.deepseek.chat.model}——
 *       于是「落库的模型 = 实际调用的模型」这条承诺对视觉场景失效，记录里写的是一个没被调用过的模型；</li>
 *   <li>{@link #hasOverrides} 认为场景声明了覆盖，引导调用方去取 options；</li>
 *   <li>{@link #optionsBuilder} 造出 {@code DeepSeekChatOptions}——而视觉 client 是 OpenAI 协议，
 *       把 DeepSeek 的 options 交给它属于类型层面的错误，是这三者里唯一真会炸的。</li>
 * </ul>
 */
@Component
public class SceneModelResolver {

    private final AIProperties aiProperties;

    /** 全局默认模型编码，与 {@code spring.ai.deepseek.chat.model} 保持单一来源。 */
    private final String defaultModelCode;

    /** 视觉场景的模型编码，与 {@code ai.vision.model} 保持单一来源。 */
    private final String visionModelCode;

    public SceneModelResolver(AIProperties aiProperties,
                              @Value("${spring.ai.deepseek.chat.model:unknown}") String defaultModelCode,
                              @Value("${ai.vision.model:unknown}") String visionModelCode) {
        this.aiProperties = aiProperties;
        this.defaultModelCode = defaultModelCode;
        this.visionModelCode = visionModelCode;
    }

    /**
     * 场景实际使用的模型编码。
     * <p>
     * 视觉场景返回的是视觉模型（阶段 1 真正调用的那个），而不是回落成 DeepSeek 的名字——
     * 否则落库的 {@code chat_message.model_code} 会与事实不符，用于成本核算时会算错账。
     */
    public String modelCode(SceneCode scene) {
        if (scene.isRequiresVision()) {
            return visionModelCode;
        }
        String configured = aiProperties.scene(scene).getModelCode();
        return StringUtils.hasText(configured) ? configured : defaultModelCode;
    }

    /**
     * 场景是否声明了请求级参数覆盖。
     * <p>
     * 视觉场景恒为 {@code false}：它的 model/temperature/max-tokens 来自 {@code ai.vision.*}，
     * 已由 {@code AiVisionClientConfig} 烤进 client 的 defaultOptions，不再走请求级 options。
     */
    public boolean hasOverrides(SceneCode scene) {
        if (scene.isRequiresVision()) {
            return false;
        }
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
     * <p>
     * 视觉场景直接拒绝：该方法只能造出 DeepSeek 的 options，交给 OpenAI 协议的视觉 client
     * 是类型层面的错误。要调视觉参数请改 {@code ai.vision.*}。
     */
    public ChatOptions.Builder<?> optionsBuilder(SceneCode scene) {
        if (scene.isRequiresVision()) {
            throw new IllegalArgumentException("场景 " + scene.getCode()
                    + " 是视觉场景，参数应配置在 ai.vision.*（已烤进 vision client 的 defaultOptions），"
                    + "不能通过请求级 options 传入");
        }
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
