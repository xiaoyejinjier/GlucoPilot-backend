package com.threadfit.glucopilot.ai.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;

/**
 * 医疗安全硬拦截：命中敏感词的请求**不发给模型**，直接返回固定话术。
 * <p>
 * 提示词里的「禁止给剂量、必须引导就医」是软约束（模型可能不遵守），这一层是硬约束，
 * 保证合规底线不依赖模型发挥。
 * <p>
 * <b>为什么不用框架自带的 {@code SafeGuardAdvisor}</b>（2026-09-28 踩坑后改为自研）：
 * 它检查的是 {@code request.prompt().getContents()}——那是 <b>整个 Prompt 的全部文本，
 * 包括 SystemMessage</b>。而我们的系统提示词里写着「严禁给出诊断、<b>开药</b>、调整降糖药/胰岛素
 * <b>剂量</b>」，正好命中词表里的「开药」「剂量」，于是**每一条消息都被拦**，
 * 表现为「你好」「我叫小明」也直接返回固定话术、模型从未被调用。
 * <p>
 * 语义上正确的做法是：**只判「用户问了什么」，而不是判「我们自己的提示词写了什么」**。
 * 因此这里只取 Prompt 里<b>最后一条用户消息</b>做匹配（从末尾往前找，不依赖 advisor 顺序，
 * 即使将来记忆注入排在本 Advisor 之前，取到的仍是本轮用户消息）。
 * <p>
 * 命中时记 WARN 日志——框架那个版本不记日志，是这次问题「静默发生」的原因之一。
 */
@Slf4j
public class MedicalGuardAdvisor implements CallAdvisor, StreamAdvisor {

    /** 命中后的兜底话术：引导就医 + 免责声明，与提示词里的合规要求一致。 */
    public static final String DEFAULT_FAILURE_RESPONSE = """
            这类问题涉及用药与治疗，超出了健康科普的范围，我不能给出具体建议。
            请带着你的血糖记录咨询内分泌科医生或临床药师，由医生结合你的具体情况判断。
            若你正在出现心慌、出冷汗、意识模糊等不适，请立即就医或拨打 120。

            > 本内容仅为健康科普参考，不能替代医生诊断与医嘱。""";

    /** 排在最前：拦截不应依赖后续 advisor 的处理结果；也保证命中时不触发记忆查询。 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    /** 已统一小写，便于大小写不敏感匹配。 */
    private final List<String> sensitiveWords;

    private final String failureResponse;

    public MedicalGuardAdvisor(List<String> sensitiveWords, String failureResponse) {
        this.sensitiveWords = sensitiveWords.stream()
                .filter(StringUtils::hasText)
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
        this.failureResponse = failureResponse;
    }

    @Override
    public String getName() {
        return "MedicalGuardAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String hit = firstSensitiveHit(request);
        if (hit != null) {
            return failureResponse(request, hit);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String hit = firstSensitiveHit(request);
        if (hit != null) {
            return Flux.just(failureResponse(request, hit));
        }
        return chain.nextStream(request);
    }

    /**
     * 只在<b>本轮用户消息</b>里找敏感词。
     * <p>
     * ⚠️ 绝不能用 {@code prompt.getContents()}：那会把系统提示词一起扫进来，而我们的安全提示词
     * 本身就在讲「开药」「剂量」，会导致全体请求被误拦（本类注释记录的正是这次事故）。
     */
    private String firstSensitiveHit(ChatClientRequest request) {
        if (sensitiveWords.isEmpty()) {
            return null;
        }
        String userText = lastUserText(request.prompt().getInstructions());
        if (userText == null) {
            return null;
        }
        String normalized = userText.toLowerCase(Locale.ROOT);
        return sensitiveWords.stream()
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);
    }

    /** 从末尾往前找最后一条有文本的用户消息（不依赖 advisor 顺序）。 */
    private static String lastUserText(List<Message> instructions) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (message instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
                return userMessage.getText();
            }
        }
        return null;
    }

    private ChatClientResponse failureResponse(ChatClientRequest request, String hit) {
        log.warn("医疗安全硬拦截命中，未调用模型：命中词={}", hit);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(failureResponse))))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }
}
