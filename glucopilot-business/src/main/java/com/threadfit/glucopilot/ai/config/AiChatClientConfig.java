package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.guard.MedicalGuardAdvisor;
import com.threadfit.glucopilot.ai.memory.ChatMemoryReadAdvisor;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.tool.AiToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AI ChatClient 装配：把提示词、Advisor 链、工具白名单一次性挂好。
 * <p>
 * <b>Advisor 链（顺序即执行顺序）</b>：
 * <ol>
 *   <li>{@link MedicalGuardAdvisor}（可选，{@code ai.guard.enabled}）——医疗安全硬拦截：
 *       命中敏感词的请求<b>根本不会发给模型</b>，直接返回固定话术。提示词里的合规约束是软约束，
 *       这一层是硬约束，保证底线不依赖模型发挥。
 *       <b>刻意不用框架的 {@code SafeGuardAdvisor}</b>：它扫的是 {@code prompt.getContents()}
 *       （含 SystemMessage），而我们的安全提示词本身就写着「开药」「剂量」，会导致**全体请求被误拦**。
 *       事故经过与正确语义见 {@link MedicalGuardAdvisor} 类注释。</li>
 *   <li>{@link ChatMemoryReadAdvisor}——注入会话历史（只读，写入仍由 Service 显式落库）。</li>
 * </ol>
 * <p>
 * <b>默认系统提示词取 CHAT 场景</b>：它同时充当安全兜底——任何忘记显式设置 system 的调用，
 * 拿到的仍是含医疗红线与免责声明的提示词，不会退化成「无系统提示词的裸模型」。
 * 其它场景在请求侧用 {@code .system(...)} 覆盖。
 * <p>
 * <b>刻意不做的事</b>：没有挂 {@code SimpleLoggerAdvisor}。项目里已明确规定不把对话正文写进日志
 * （见 {@code application-dev.yaml} 对 Mapper 日志的处理）。需要临时排障时，可临时注册
 * {@code new SimpleLoggerAdvisor()}，或打开框架开关
 * {@code spring.ai.chat.client.observations.log-prompt: true}，用完务必关掉。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiChatClientConfig {

    @Bean
    public ChatClient textChatClient(ChatClient.Builder autoConfiguredBuilder,
                                     AiPromptTemplateService promptTemplateService,
                                     ChatMemoryReadAdvisor chatMemoryReadAdvisor,
                                     AiToolRegistry toolRegistry,
                                     AIProperties aiProperties) {
        List<Advisor> advisors = new ArrayList<>();
        Advisor medicalGuard = medicalGuard(aiProperties);
        if (medicalGuard != null) {
            advisors.add(medicalGuard);
        }
        advisors.add(chatMemoryReadAdvisor);

        // 自动装配的 Builder 是单例，clone() 后再改，避免污染框架/其它使用方的默认配置。
        //
        // ⚠️ 必须从注入的 Builder clone，不能改用静态工厂 ChatClient.builder(model) 手搓：
        // 工具是由 ToolCallingAdvisor 执行的，而该 Advisor 只存在于自动装配的 Builder 上
        // （由 ChatClientAutoConfiguration 注入）。手搓的 client 会静默丢掉全部工具与观测。
        //
        // 视觉 provider（{@link AiVisionClientConfig}）相反，是**有意手搓**的：
        // 它挂在另一个 ChatModel 上、根本无法从本 Builder clone，而且阶段 1「看图出描述」
        // 不需要也不应该有工具。两处结论相反，判据是「这个 client 要不要工具」，不是「能不能 clone」。
        return autoConfiguredBuilder.clone()
                .defaultSystem(promptTemplateService.systemPrompt(SceneCode.CHAT))
                .defaultTemplateRenderer(promptTemplateService.renderer())
                .defaultAdvisors(advisors)
                // 用 defaultTools 而不是 defaultToolCallbacks：后者三个重载在 2.0.1 已全部过时。
                // ⚠️ 未验证项：这条装配路径尚无端到端测试（当前 ai.tools.allow-list 为空，无工具可验）。
                // 实现第一个真实工具时，必须先用「假 ChatModel 返回 tool_calls 回合」的测试
                // 确认工具确实被调用，再上生产。
                .defaultTools(toolRegistry.callbacks().toArray())
                .build();
    }

    /** 未启用或未配置敏感词时不挂该 Advisor——空词表会给人一种「已经拦住了」的错觉。 */
    private static Advisor medicalGuard(AIProperties aiProperties) {
        AIProperties.GuardProperties guard = aiProperties.getGuard();
        if (!guard.isEnabled() || guard.getSensitiveWords() == null || guard.getSensitiveWords().isEmpty()) {
            log.warn("医疗安全硬拦截未启用（ai.guard.enabled/sensitive-words），当前仅依赖提示词的软约束");
            return null;
        }
        String failureResponse = StringUtils.hasText(guard.getFailureResponse())
                ? guard.getFailureResponse()
                : MedicalGuardAdvisor.DEFAULT_FAILURE_RESPONSE;
        log.info("医疗安全硬拦截已启用（只判用户消息，不判系统提示词），敏感词 {} 个",
                guard.getSensitiveWords().size());
        return new MedicalGuardAdvisor(guard.getSensitiveWords(), failureResponse);
    }
}
