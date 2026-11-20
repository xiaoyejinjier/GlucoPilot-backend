package com.threadfit.glucopilot.ai.advisor;

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
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextShard;
import com.threadfit.glucopilot.ai.business.chat.mapper.MessageGap;
import com.threadfit.glucopilot.ai.business.chat.mapper.SummaryState;
import com.threadfit.glucopilot.ai.memory.reader.ChatContextReader;
import com.threadfit.glucopilot.ai.memory.assembly.ChatHistoryAssembler;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;

/**
 * 只读的会话记忆 Advisor：按 <b>token 预算</b>裁剪历史并注入本轮请求（② 期改造）。
 *
 * <h2>为什么不用框架自带的 {@code MessageChatMemoryAdvisor}</h2>
 * 它会<b>写</b>记忆（调用 {@code ChatMemory.add}），而本项目的助手消息必须等流式结束、
 * 拿到完整正文与 token / finish_reason / latency 之后才落库——两者会写出重复且残缺的数据，
 * 还会让「按 reply_to_msg_id 幂等重放」失效。所以这里只复用它「读」的语义。
 *
 * <h2>⚠️ 裁剪为什么在这一层</h2>
 * 核对框架字节码后确认 {@code MessageWindowChatMemory.get()} 是<b>纯透传</b>，裁剪只在 {@code add()}
 * 路径上，而本项目从不走 {@code add()}。因此窗口只能在读取侧施加：仓库负责"最多捞多少"
 * （{@code ai.memory.max-fetch}，防御性上限），这里负责"送多少进 prompt"。
 *
 * <h2>② 期：从"条数"改成"token 预算"</h2>
 * 实测一轮几乎空的对话（系统提示词 1651 字 + 6 个工具定义）就已经 5012 prompt tokens，
 * 而单条助手回复上限 20 000 字、回放时不做逐条截断——"20 条"能装的内容量可以差两个数量级。
 * 现在由 {@link ChatHistoryAssembler} 按预算装配（先扣固定开销 → 从最近往回装 → 保底条数），
 * 并把**滚动摘要**与**最近工具结果摘要**作为一条附加上下文注入。
 *
 * <h2>回调线程</h2>
 * ⚠️ Advisor 回调<b>不在请求线程上</b>（框架用调度器），因此这里绝不能碰 {@code RequestContext}；
 * 所需标识（会话 ID、固定开销、估算回写槽）都通过 advisor 参数在进入流式之前放好。
 */
@Slf4j
public class ChatMemoryReadAdvisor implements CallAdvisor, StreamAdvisor {

    /** 与框架记忆 Advisor 同级优先级，保证它在终端「调用模型」的 Advisor 之前执行。 */
    private static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    private final ChatMemory chatMemory;

    private final ChatHistoryAssembler assembler;

    private final ChatContextReader contextReader;

    public ChatMemoryReadAdvisor(ChatMemory chatMemory,
                                 ChatHistoryAssembler assembler,
                                 ChatContextReader contextReader) {
        this.chatMemory = chatMemory;
        this.assembler = assembler;
        this.contextReader = contextReader;
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
        Long sessionId = sessionId(request);
        List<Message> current = request.prompt().getInstructions();
        if (sessionId == null) {
            // 未传会话 ID（无状态调用，例如摘要压缩自己）：不注入任何历史
            return request;
        }
        List<Message> history = chatMemory.get(sessionId.toString());
        // 摘要正文与「覆盖到哪一条」一次取回：装配日志需要两者才能判断摘要够不够用（③ 期）
        SummaryState summaryState = contextReader.summaryState(sessionId);
        String summary = summaryState == null ? null : summaryState.getText();
        List<String> toolDigests = contextReader.recentToolDigests(sessionId);
        // ③ 期：长期记忆是用户维度（与 sessionId 无关），userId 由调用方放进 advisor 参数
        Long userId = userId(request);
        List<UserChatMemoryService.MemoryItem> memories = contextReader.longTermMemory(userId);
        int fixedOverhead = fixedOverhead(request);
        // 必留带：摘要指针之后的消息在本轮不允许被条数上限丢掉（否则那几条既不在摘要、
        // 也不在窗口里，模型直接断片）。没有摘要指针时退回 0，仍由 keep-recent 保底——
        // 那种情况下"空洞"这个概念本身不成立（一条都还没被摘要覆盖）。
        int protectedTail = 0;
        if (summaryState != null && summaryState.getUptoMsgId() != null) {
            protectedTail = contextReader.countUnsummarized(sessionId, summaryState.getUptoMsgId());
        }

        ChatHistoryAssembler.Assembled assembled = assembler.assemble(history, current, summary, toolDigests,
                memories, fixedOverhead, protectedTail);
        ChatHistoryAssembler.Stats stats = assembled.stats();
        if (stats.protectedDropped() > 0) {
            // 预算与"不漏消息"撞车时，按约定预算优先（cost 可控优先），但必须留下证据：
            // 这条 WARN 说明这一轮确实丢了 N 条未摘要内容，用户可能会发现模型"忘了刚才说的"
            log.warn("预算不足，本轮被迫丢掉 {} 条未摘要消息 sessionId={} 预算={} 估算={}；"
                            + "可调大 ai.memory.max-prompt-tokens，或调小 summary-min-pending-messages 让摘要更勤",
                    stats.protectedDropped(), sessionId, stats.budgetTokens(), stats.estimatedTokens());
        }
        String summaryLog = summaryLog(sessionId, summaryState, stats.droppedHistory());
        if (stats.droppedHistory() > 0 || stats.summaryUsed() || stats.toolDigestUsed() || stats.memoryItems() > 0) {
            // 「记忆=N」是排查"用户说它记不住"的第一现场：N=0 说明一条长期记忆都没进 prompt
            // （没查到 / 被过滤 / userId 没传到 advisor 参数），N>0 才说明注入到位。
            // 「空洞」是排查"模型忘了刚才聊过什么"的现场：摘要覆盖到 #a、窗口从 #b 开始，
            // a 与 b 之间那几条既不在摘要也不在窗口里，模型看不到（见 ai.memory.window-messages）。
            log.info("会话记忆装配 sessionId={} 记忆={} 保留历史={} 必留={} 裁剪={} 窗口滑动={} 估算token={} 预算={} {} 工具摘要={}"
                            + (stats.budgetExceeded() ? "（超预算：保底条数优先）" : ""),
                    sessionId, stats.memoryItems(), stats.keptHistory(), stats.protectedHistory(),
                    stats.droppedHistory(), stats.windowShifted(), stats.estimatedTokens(),
                    stats.budgetTokens(), summaryLog, stats.toolDigestUsed());
        } else {
            log.debug("会话记忆装配 sessionId={} 保留历史={} 估算token={}", sessionId,
                    stats.keptHistory(), stats.estimatedTokens());
        }
        writeBackEstimate(request, stats);
        // ③ 期：把本轮的分片元数据交给调用方（它会在落库、拿到 assistant_msg_id 之后写 chat_context）
        writeBackShards(request, assembled.shards());

        return request.mutate()
                .prompt(new Prompt(assembled.messages(), request.prompt().getOptions()))
                .context(request.context())
                .build();
    }

    /**
     * 组装日志里的「摘要」片段（③ 期）：字数、覆盖到哪一条、以及摘要与窗口之间的空洞。
     *
     * <p>三种情形刻意分开写：没有摘要（还没攒够）、有摘要但没裁剪（窗口从会话开头装下了，不可能有空洞，
     * 也就不查库）、有摘要且裁剪过（此时才查一次空洞）。这样平时每轮只多出一次主键点查，
     * 只有真的发生裁剪时才多一条聚合查询。
     */
    private String summaryLog(Long sessionId, SummaryState state, int dropped) {
        if (state == null) {
            return "摘要=无";
        }
        StringBuilder text = new StringBuilder();
        text.append("摘要=").append(state.getText() == null ? 0 : state.getText().length()).append("字");
        text.append(" 覆盖到#").append(state.getUptoMsgId() == null ? "无" : state.getUptoMsgId());
        if (dropped <= 0) {
            text.append(" 空洞=0（未裁剪）");
            return text.toString();
        }
        MessageGap gap = contextReader.messageGap(sessionId, state.getUptoMsgId(), dropped);
        if (gap == null || gap.getMissing() == null) {
            text.append(" 空洞=未知");
            return text.toString();
        }
        text.append(" 空洞=").append(gap.getMissing());
        if (gap.getMissing() > 0) {
            text.append(" 起于#").append(gap.getFirstMissingId());
            log.warn("摘要与窗口之间有 {} 条消息既未进摘要也未进窗口 sessionId={} 起于#{}；"
                            + "可调大 ai.memory.window-messages 或 keep-recent-messages，或让摘要更勤",
                    gap.getMissing(), sessionId, gap.getFirstMissingId());
        }
        return text.toString();
    }

    /** 会话 ID：框架的 advisor 参数里带（{@code ChatCompletionService} 注入）。 */
    private static Long sessionId(ChatClientRequest request) {
        Object value = request.context().get(ChatMemory.CONVERSATION_ID);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            log.warn("非法的 conversationId（应为 chat_session.id）：{}", value);
            return null;
        }
    }

    /** 当前用户 ID（③ 期：长期记忆按用户维度取；缺失时按"没有记忆"处理）。 */
    private static Long userId(ChatClientRequest request) {
        Object value = request.context().get(ChatMemoryAdvisorParams.USER_ID);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            log.warn("advisor 参数里的 userId 不是合法数字：{}", value);
            return null;
        }
    }
    /** 固定开销（系统提示词 + 工具定义）的估算：调用方算好放在 advisor 参数里，缺省 0。 */
    private static int fixedOverhead(ChatClientRequest request) {
        Object value = request.context().get(ChatMemoryAdvisorParams.FIXED_OVERHEAD_TOKENS);
        return value instanceof Number number ? Math.max(number.intValue(), 0) : 0;
    }

    /**
     * 把本轮估算写回调用方持有的槽位，供其在落库后与厂商返回的真实 {@code prompt_tokens} 比对校准。
     * 槽位是调用方放进 advisor 参数的一个 {@link AtomicInteger}（同一个对象引用）。
     */
    private static void writeBackEstimate(ChatClientRequest request, ChatHistoryAssembler.Stats stats) {
        Object holder = request.context().get(ChatMemoryAdvisorParams.PROMPT_ESTIMATE);
        if (holder instanceof AtomicInteger slot) {
            slot.set(stats.estimatedTokens());
        }
    }

    /**
     * 把本轮的分片元数据写回调用方持有的槽位（③ 期）。
     * <p>
     * 与 {@link #writeBackEstimate} 同一个套路，但这里是<b>整组</b>数据：装配器已经把
     * "系统提示 / 长期记忆 / 摘要 / 工具结果 / 历史消息"各占多少 token 算好了
     * （见 {@link ChatHistoryAssembler#assemble}），这里只负责原样交出去，
     * <b>不再二次估算</b>——两处各算一遍，迟早会出现"日志里的 token 和 chat_context 里的不一样"。
     */
    private static void writeBackShards(ChatClientRequest request, List<ChatContextShard> shards) {
        Object holder = request.context().get(ChatMemoryAdvisorParams.CONTEXT_SHARDS);
        if (holder instanceof java.util.concurrent.atomic.AtomicReference<?> slot) {
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<List<ChatContextShard>> typed =
                    (java.util.concurrent.atomic.AtomicReference<List<ChatContextShard>>) slot;
            typed.set(shards == null ? List.of() : shards);
        }
    }

    /** 供单测直接调用（不经过 client）。 */
    ChatClientRequest withMemoryForTest(ChatClientRequest request) {
        return withMemory(request);
    }
}
