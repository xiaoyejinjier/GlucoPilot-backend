package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉 provider（阿里云百炼 qwen-vl，OpenAI 兼容协议）的 {@code ChatClient} 装配。
 *
 * <h2>与 {@link AiChatClientConfig} 的两处刻意不同，都是设计选择而不是疏漏</h2>
 * <ol>
 *   <li><b>不从自动装配的 {@code ChatClient.Builder} clone，而是自己 build 一个。</b>
 *       {@code AiChatClientConfig} 明确写着「必须 clone，不能手搓」，那是因为文本 client 需要
 *       {@code ToolCallingAdvisor}（工具只挂在自动装配的 Builder 上），手搓会静默丢工具。
 *       本 client 只做一件事——阶段 1 看图出描述，<b>不需要工具，也不应该带工具</b>：
 *       让视觉模型在"描述一张图"的过程中触发工具调用，只会引入无法预期的副作用。
 *       注意"自己 build ChatClient"与"自己造 ChatModel"是两件事：后者已经下沉到
 *       {@code ChatModelFactory}，本类只保留"取哪个 ChatModel + 挂哪份提示词"。</li>
 *   <li><b>不挂 Advisor 链（无医疗拦截、无会话记忆注入）。</b>
 *       记忆注入会把整段对话历史发给视觉模型，纯属浪费 token；而医疗拦截的输入是
 *       "请识别这张图片"这种固定指令，拦它没有意义。图片场景真正的安全处理在阶段 2：
 *       那里走文本 client，挂着完整的 CHAT 提示词（含医疗红线与免责声明）。</li>
 * </ol>
 *
 * <h2>2026-10-12 改造：ChatModel 由"启动期烤死"改为"运行时委托"</h2>
 * 原先这里用 {@code OpenAiChatModel.builder().options(...)} 把 baseUrl/apiKey/model/超时全部固化在启动期，
 * 于是后台改视觉模型名必须重新打包重启。而实测踩过的正是这个坑：模型名写成账号下不存在的
 * {@code qwen3-vl-flash}，只能等用户发图才看到 {@code 404 Model not exist}。
 * 现在这些字段由 {@code ai_model_config}（DB 优先、yaml 兜底）在每次调用前解析，
 * ChatModel 由 {@code glucopilotVisionChatModel}（{@code DynamicChatModel}）提供。
 *
 * <h2>为什么不引 starter，但也不手搓 {@code OpenAIClient}</h2>
 * 不引 {@code spring-ai-starter-model-openai} 的理由见 pom：它会带来第二套自动装配
 * （向量/图片/语音等）以及第二个 {@code ChatModel} Bean，与「容器里只能有一个 ChatModel」冲突。
 * <p>
 * 但据此以为要自己 {@code new OpenAIClientImpl(ClientOptions...)} <b>是错的</b>，会让应用启动直接失败。
 * 踩到的坑记在这里，避免再犯（现在这条约束落在 {@code ChatModelFactory} 里）：
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
 *
 * <h2>Bean 命名与注入歧义</h2>
 * 本 Bean 名为 {@code visionChatClient}，与 {@code textChatClient} 同类型。任何 {@code ChatClient}
 * 注入点都必须带 {@code @Qualifier}，否则启动期就会因候选不唯一失败
 * （见 {@link com.threadfit.glucopilot.ai.core.AiChatClientRegistry}）。ChatModel 侧同理：
 * 容器里有 {@code DeepSeekChatModel}、{@code glucopilotTextChatModel}（@Primary）、
 * {@code glucopilotVisionChatModel} 三个，所以这里显式按名字取。
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.vision", name = "enabled", havingValue = "true")
public class AiVisionClientConfig {

    @Bean
    public ChatClient visionChatClient(@Qualifier("glucopilotVisionChatModel") ChatModel visionChatModel,
                                       AiPromptTemplateService promptTemplateService) {
        return ChatClient.builder(visionChatModel)
                .defaultSystem(promptTemplateService.systemPrompt(SceneCode.CHAT_IMAGE))
                .defaultTemplateRenderer(promptTemplateService.renderer())
                .build();
    }
}
