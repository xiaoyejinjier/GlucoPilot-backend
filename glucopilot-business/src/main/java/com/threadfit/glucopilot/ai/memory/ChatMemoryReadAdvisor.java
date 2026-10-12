package com.threadfit.glucopilot.ai.memory;

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
 * 只读的会话记忆 Advisor：把 {@link ChatMemory} 里的历史消息按窗口裁剪后注入本轮请求。
 * <p>
 * <b>为什么不用框架自带的 {@code MessageChatMemoryAdvisor}</b>：它会<b>写</b>记忆
 * （调用 {@code ChatMemory.add}），而本项目的助手消息必须等流式结束、拿到完整正文与
 * token / finish_reason / latency 之后才落库——两者会写出重复且残缺的数据，还会让
 * 「按 reply_to_msg_id 幂等重放」失效。所以这里只复用它「读」的语义：
 * 由自定义 Advisor 注入历史，写入仍由 {@code ChatCompletionService} 显式完成。
 * <p>
 * <b>⚠️ 窗口裁剪为什么在这一层（2026-10-12 修正）</b>：原先的注释断言「窗口语义由框架的
 * {@code MessageWindowChatMemory} 负责」，核对框架字节码后发现那只对<b>写入路径</b>成立：
 * {@code MessageWindowChatMemory.get()} 是<b>纯透传</b>（直接 {@code return repository.findByConversationId(id)}，
 * 没有任何裁剪），裁剪只发生在私有方法 {@code process(...)} 里、而它只被 {@code add(...)} 调用。
 * 本项目从不走 {@code add()}（{@code saveAll()} 主动抛异常，写入由业务 Service 完成），
 * 于是 {@code maxMessages} 成了死配置，实际上送进模型的是仓库的 {@code LIMIT}（{@code ai.memory.max-fetch}=200），
 * 而不是 {@code ai.memory.window-messages}=20——配置、字段注释与真实行为三者不一致。
 * 所以窗口只能在<b>读取侧的我们这层</b>施加：仓库负责"最多捞多少"（防御性上限），
 * 这里负责"送多少进 prompt"。
 * <p>
 * 挂载方式：作为 {@code ChatClient} 的 default advisor，请求侧通过
 * {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))} 传会话 ID。
 * 未传会话 ID 时直接放行（无状态调用，例如冒烟/一次性推理）。
 * <p>
 * ⚠️ Advisor 回调<b>不在请求线程上</b>（框架用 {@code BaseAdvisor.DEFAULT_SCHEDULER} 调度），
 * 因此这里绝不能碰 {@code RequestContext}（ThreadLocal）——所需标识必须在进入流式之前取好。
 * （实测：本类直接实现 {@code CallAdvisor}/{@code StreamAdvisor}、没有 {@code getScheduler()} 那层间接，
 * 所以当前跑在请求线程上；但这条警告仍然成立——不能依赖它。）
 */
@Slf4j
public class ChatMemoryReadAdvisor implements CallAdvisor, StreamAdvisor {

    /** 与框架记忆 Advisor 同级优先级，保证它在终端「调用模型」的 Advisor 之前执行。 */
    private static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    private final ChatMemory chatMemory;

    /** 送入模型的最近原文消息条数上限（{@code ai.memory.window-messages}）。 */
    private final int windowMessages;

    public ChatMemoryReadAdvisor(ChatMemory chatMemory, int windowMessages) {
        this.chatMemory = chatMemory;
        this.windowMessages = windowMessages;
        if (windowMessages <= 0) {
            // 静默把窗口当 0 会一句话都不带、把当"不裁剪"又会把 max-fetch 全塞进 prompt，
            // 两种猜测都可能让线上表现莫名变化，所以显式告警并选择"不裁剪"这个可解释的行为
            log.warn("ai.memory.window-messages={} 非法（应为正整数），窗口裁剪已禁用："
                    + "将把仓库返回的全部历史（上限 max-fetch）送入模型", windowMessages);
        }
    }

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
        // 窗口在这里施加：框架的 ChatMemory.get() 不裁剪（见类注释），仓库只负责"最多捞多少"
        List<Message> history = keepLastWindow(chatMemory.get(conversationId), windowMessages);
        if (history.isEmpty()) {
            return request;
        }
        List<Message> current = request.prompt().getInstructions();
        List<Message> merged = merge(history, current);
        log.debug("注入会话记忆 conversationId={} history={} 条（窗口上限 {}）",
                conversationId, history.size(), windowMessages);
        return request.mutate()
                .prompt(new Prompt(merged, request.prompt().getOptions()))
                .context(request.context())
                .build();
    }

    /**
     * 只保留最近 {@code window} 条（历史是时间正序，所以取末尾）。
     * <p>
     * {@code window <= 0} 视为"未配置/配错"，原样返回（不裁剪）——与之配套的告警在构造器里，
     * 这样行为可解释，也不会因为一个错配置把历史静默清空。
     * 包级可见，便于单测直接覆盖。
     */
    static List<Message> keepLastWindow(List<Message> history, int window) {
        if (window <= 0 || history.size() <= window) {
            return history;
        }
        // 复制一份而不是返回 subList 视图：视图会跟着原列表走，并让"已裁剪"这件事变得不直观
        return new ArrayList<>(history.subList(history.size() - window, history.size()));
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
