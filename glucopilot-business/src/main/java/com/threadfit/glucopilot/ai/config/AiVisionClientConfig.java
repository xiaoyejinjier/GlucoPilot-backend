package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 视觉 provider（阿里云百炼 qwen-vl，OpenAI 兼容协议）的 {@code ChatClient} 装配。
 * <p>
 * <b>与 {@link AiChatClientConfig} 的两处刻意不同，都是设计选择而不是疏漏：</b>
 * <ol>
 *   <li><b>不从自动装配的 {@code ChatClient.Builder} clone，而是手搓。</b>
 *       {@code AiChatClientConfig} 里明确写着「必须 clone，不能手搓」，那是因为文本 client 需要
 *       {@code ToolCallingAdvisor}（工具只挂在自动装配的 Builder 上），手搓会静默丢工具。
 *       本 client 的用途只有一件事——阶段 1 看图出描述，<b>不需要工具，也不应该带工具</b>：
 *       让视觉模型在「描述一张图」的过程中触发工具调用，只会引入无法预期的副作用。</li>
 *   <li><b>不挂 Advisor 链（无医疗拦截、无会话记忆注入）。</b>
 *       记忆注入会把整段对话历史发给视觉模型，纯属浪费 token；而医疗拦截的输入是
 *       「请识别这张图片」这种固定指令，拦它没有意义。
 *       图片场景真正的安全处理在阶段 2：那里走的是文本 client，挂着完整的 CHAT 提示词
 *       （含医疗红线与免责声明）。</li>
 * </ol>
 * <p>
 * <b>为什么不引 starter，但也不手搓 {@code OpenAIClient}：</b>
 * <p>
 * 不引 {@code spring-ai-starter-model-openai} 的理由见 pom：它会带来第二套自动装配
 * （向量/图片/语音等）以及第二个 {@code ChatModel} Bean，与「容器里只能有一个 ChatModel」冲突。
 * <p>
 * 但一开始我据此以为要自己 {@code new OpenAIClientImpl(ClientOptions...)}，<b>那是错的</b>，
 * 会让应用启动直接失败。踩到的坑记在这里，避免再犯：
 * <ul>
 *   <li>{@code OpenAiChatModel.Builder.openAiClient(...)} <b>只设置同步 client</b>，
 *       异步 client 仍是 null；{@code build()} 里对它是
 *       {@code requireNonNullElseGet(asyncClient, () -> setupAsyncClient(...))} 兜底构造，
 *       而 {@code setupAsyncClient} <b>只从 {@code OpenAiChatOptions} 的 baseUrl/apiKey 取凭据</b>。</li>
 *   <li>于是「同步 client 给了 key、options 里没给 key」这种组合会抛出
 *       {@code At least one credential source must be specified: credential (apiKey), ...}——
 *       报错指向 {@code build()} 而不是我们的构造代码，极易误判成「配置没读到」。</li>
 * </ul>
 * <b>正确做法：根本不碰 {@code OpenAIClient}，把 baseUrl / apiKey / timeout 放进
 * {@code OpenAiChatOptions}，由 Spring AI 自己构造同步与异步两个 client。</b>
 * 这样也顺带用上了 Spring AI 自带的 okhttp 传输层与观测，不必依赖 autoconfigure 模块。
 * <p>
 * <b>Bean 命名与注入歧义：</b>本 Bean 名为 {@code visionChatClient}，与本模块的
 * {@code textChatClient} 同类型。任何 {@code ChatClient} 注入点都必须带 {@code @Qualifier}，
 * 否则启动期就会因候选不唯一失败（见 {@link com.threadfit.glucopilot.ai.core.AiChatClientRegistry}）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "ai.vision", name = "enabled", havingValue = "true")
public class AiVisionClientConfig {

    /**
     * 视觉场景（阶段 1）的系统提示词取 {@link SceneCode#CHAT_IMAGE} 的提示词文件。
     * 这不是巧合：该场景在 {@link SceneCode} 里的定义就是「视觉模型先识别」，
     * 而阶段 2 复用 {@link SceneCode#CHAT}，因此 {@code SceneCode} 与
     * {@link AiPromptTemplateService} 都无需为本功能新增任何场景。
     */
    @Bean
    public ChatClient visionChatClient(AIProperties aiProperties,
                                       AiPromptTemplateService promptTemplateService) {
        AIProperties.VisionProperties vision = aiProperties.getVision();
        require(vision.getBaseUrl(), "ai.vision.base-url");
        require(vision.getApiKey(), "ai.vision.api-key");
        require(vision.getModel(), "ai.vision.model");

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(vision.getBaseUrl())
                        .apiKey(vision.getApiKey())
                        .model(vision.getModel())
                        .temperature(vision.getTemperature())
                        .maxCompletionTokens(vision.getMaxTokens())
                        .timeout(Duration.ofSeconds(vision.getTimeoutSeconds()))
                        .build())
                .build();

        // 不打印 apiKey；baseUrl 打出来是为了区分「连的是百炼还是别的兼容端点」这类低级事故
        log.info("视觉 provider 已装配 model={} baseUrl={} timeout={}s maxTokens={}",
                vision.getModel(), vision.getBaseUrl(), vision.getTimeoutSeconds(), vision.getMaxTokens());

        // 系统提示词由每次调用按 SceneCode 注入，同一个视觉 client 才能安全复用到 CHAT_IMAGE / OCR_GLUCOSE。
        return ChatClient.builder(chatModel)
                .defaultTemplateRenderer(promptTemplateService.renderer())
                .build();
    }

    /** 启动期 fail-fast：开了开关却缺凭据，必须在这里就炸，而不是等用户发图才报错。 */
    private static void require(String value, String key) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("ai.vision.enabled=true 但 " + key + " 未配置");
        }
    }
}
