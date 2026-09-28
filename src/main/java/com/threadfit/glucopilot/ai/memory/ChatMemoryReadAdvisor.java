package com.threadfit.glucopilot.ai.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 只读的会话记忆 Advisor：把 {@link ChatMemory} 里的历史消息注入本轮请求。
 * <p>
 * <b>为什么不用框架自带的 {@code MessageChatMemoryAdvisor}</b>：它会<b>写</b>记忆
 * （调用 {@code ChatMemory.add}），而本项目的助手消息必须等流式结束、拿到完整正文与
 * token / finish_reason / latency 之后才落库——两者会写出重复且残缺的数据，还会让
 * 「按 reply_to_msg_id 幂等重放」失效。所以这里只复用它「读」的语义：
 * 由自定义 Advisor 注入历史，写入仍由 {@code ChatCompletionService} 显式完成。
 * <p>
 * 挂载方式：作为 {@code ChatClient} 的 default advisor，请求侧通过
 * {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))} 传会话 ID。
 * 未传会话 ID 时直接放行（无状态调用，例如冒烟/一次性推理）。
 * <p>
 * ⚠️ Advisor 回调<b>不在请求线程上</b>（框架用 {@code BaseAdvisor.DEFAULT_SCHEDULER} 调度），
 * 因此这里绝不能碰 {@code RequestContext}（ThreadLocal）——所需标识必须在进入流式之前取好。
 */
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryReadAdvisor implements CallAdvisor, StreamAdvisor {

    /** 与框架记忆 Advisor 同级优先级，保证它在终端「调用模型」的 Advisor 之前执行。 */
    private static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    private final ChatMemory chatMemory;

    @Override
    public String getName() {
        return "ChatMemoryReadAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(withMemory(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(withMemory(request));
    }

    private ChatClientRequest withMemory(ChatClientRequest request) {
        String conversationId = conversationId(request);
        if (conversationId == null) {
            return request;
        }
        List<Message> history = chatMemory.get(conversationId);
        if (history.isEmpty()) {
            return request;
        }
        List<Message> current = request.prompt().getInstructions();
        List<Message> merged = merge(history, current);
        log.debug("注入会话记忆 conversationId={} history={} 条", conversationId, history.size());
        return request.mutate()
                .prompt(new Prompt(merged, request.prompt().getOptions()))
                .context(request.context())
                .build();
    }

    /** 合并历史与本轮消息（顺序：历史 → 本轮）。包级可见，便于单测直接覆盖去重逻辑。 */
    static List<Message> merge(List<Message> history, List<Message> current) {
        List<Message> merged = new ArrayList<>(history.size() + current.size());
        merged.addAll(dropTrailingDuplicate(history, current));
        merged.addAll(current);
        return merged;
    }

    private static String conversationId(ChatClientRequest request) {
        Object value = request.context().get(ChatMemory.CONVERSATION_ID);
        if (value == null) {
            return null;
        }
        String id = String.valueOf(value);
        return id.isBlank() ? null : id;
    }

    /**
     * 本轮用户消息在调用补全接口之前<b>已经落库</b>（接口 2.24 先写消息，2.25 再开流），
     * 因此历史末尾会包含本轮这条用户消息；若请求里也带了同一条，会造成重复送入。
     * 这里按「文本相同」去掉历史末尾这一条。
     */
    private static List<Message> dropTrailingDuplicate(List<Message> history, List<Message> current) {
        if (history.isEmpty() || current.isEmpty()) {
            return history;
        }
        Message lastHistory = history.get(history.size() - 1);
        if (!(lastHistory instanceof UserMessage lastUser)) {
            return history;
        }
        String lastHistoryText = lastUser.getText();
        if (lastHistoryText == null) {
            return history;
        }
        boolean duplicated = current.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(Objects::nonNull)
                .anyMatch(lastHistoryText::equals);
        if (!duplicated) {
            return history;
        }
        return history.subList(0, history.size() - 1);
    }
}
