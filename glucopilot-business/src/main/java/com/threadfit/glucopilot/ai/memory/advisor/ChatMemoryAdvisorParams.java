package com.threadfit.glucopilot.ai.memory.advisor;

import com.threadfit.glucopilot.ai.memory.assembly.ChatContextShard;
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

    private ChatMemoryAdvisorParams() {
    }
}
