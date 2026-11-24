package com.threadfit.glucopilot.ai.advisor;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextShard;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;
/**
 * 记忆装配相关 advisor 参数的键（② 期）。
 * <p>
 * 这些值由 {@code ChatCompletionService} 在发起请求前放进 advisor 参数（它与 Advisor 之间的
 * 唯一通信通道），而不是靠 ThreadLocal：Advisor 回调不在请求线程上，ThreadLocal 取不到。
 */
public final class ChatMemoryAdvisorParams {

    /**
     * 固定开销的估算 token（系统提示词 + 工具定义）。
     * <p>
     * 为什么要传进来：装配器只知道"历史 + 摘要"要花多少，而真实 prompt 里还有一大块固定内容
     * （实测约 5k）。不扣掉它就会系统性超预算。
     */
    public static final String FIXED_OVERHEAD_TOKENS = "fixedOverheadTokens";

    /**
     * 本轮估算 token 的写回槽（一个 {@code AtomicInteger} 引用）。
     * <p>
     * 用途：落库时拿它和厂商返回的真实 {@code prompt_tokens} 比对，校准估算器。
     * 用"调用方持有同一个对象引用"的方式回传，避免为了一个数字去改 Advisor 的返回值契约。
     */
    public static final String PROMPT_ESTIMATE = "promptEstimate";

    /**
     * 当前用户 ID（③ 期新增）：长期记忆按用户维度取，而 advisor 回调线程上取不到 RequestContext，
     * 只能由调用方在发起请求前放进来。缺失时按"没有长期记忆"处理（不影响其它记忆装配）。
     */
    public static final String USER_ID = "userId";

    /**
     * 本轮分片元数据的回传槽（③ 期新增）：一个 {@code AtomicReference<List<ChatContextShard>>}。
     * <p>
     * 写法与 {@link #PROMPT_ESTIMATE} 完全一致——<b>调用方持有对象引用，Advisor 往里写</b>。
     * 为什么不改成 Advisor 的返回值：那会改变 Advisor 链的契约，而且要动每一层；
     * 而这里需要的只是一个"装配阶段算出来、落库阶段才用得上"的数据，正是这种槽的用途。
     * <p>
     * 取值时机在<b>正文落库之后</b>：分片行要回填 {@code assistant_msg_id}，
     * 而助手消息的 ID 要等模型跑完、INSERT 之后才有。
     */
    public static final String CONTEXT_SHARDS = "contextShards";

    /**
     * 「待压条数」的写回槽（一个 {@code AtomicInteger}）：摘要指针之后、还没被摘要覆盖的消息条数。
     * <p>
     * 为什么不直接让 Advisor 决定"要不要摘要"：装配阶段确实已经算出这个数（正是必留带的输入），
     * 但压不压决定的是<b>异步任务</b>，而 Advisor 只能影响本轮 prompt。写回之后由
     * {@code ChatCompletionService} 连同预算一起交给摘要服务——判据只有一个来源（同一轮装配算出的
     * 那个数），不会出现"advisor 说装得下、摘要服务说该压"的自相矛盾。
     */
    public static final String PROTECTED_TAIL = "protectedTail";

    /**
     * 「待压字符数」的写回槽（一个 {@code AtomicInteger}）：必留带里那些消息的正文合计长度。
     * <p>
     * 与 {@link #PROTECTED_TAIL} 一起构成防抖下限：语音转写的两三条长消息可能不到 6 条，
     * 但已经上千字，按条数会被判"太小不值得压"，按字符才不会。
     */
    public static final String PROTECTED_CHARS = "protectedChars";

    /**
     * 「上一轮被预算挤掉的未摘要条数」的写回槽（一个 {@code AtomicInteger}）。
     * <p>
     * 这是摘要最强的触发信号：它大于 0 意味着上一轮用户说的话<b>已经</b>没能进 prompt。
     * 等到下一次"水位过高"再压就晚了——模型已经断片过一次。
     */
    public static final String PROTECTED_DROPPED = "protectedDropped";

    /**
     * 「本轮预算」的写回槽（一个 {@code AtomicInteger}）：{@code ai.memory.max-prompt-tokens}。
     * <p>
     * 装配器本来就按预算裁剪，直接把<b>它用的那个数</b>交出来，比调用方自己再读一次配置更可靠
     * （配置热更新/多实例时以装配为准）；摘要门判断"估算是否顶到预算"要用同一个分母。
     */
    public static final String PROMPT_BUDGET = "promptBudget";

    private ChatMemoryAdvisorParams() {
    }
}
