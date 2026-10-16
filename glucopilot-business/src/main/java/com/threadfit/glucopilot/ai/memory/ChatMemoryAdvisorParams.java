package com.threadfit.glucopilot.ai.memory;

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

    private ChatMemoryAdvisorParams() {
    }
}
