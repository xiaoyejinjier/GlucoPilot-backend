package com.threadfit.glucopilot.ai.memory.service;

import java.util.Map;

/**
 * 会话滚动摘要契约（② 期）：把超出记忆窗口的旧对话压缩成"事实摘要"，供后续轮次作为背景注入。
 *
 * <h2>为什么是异步 + 单飞</h2>
 * 摘要是"回复之后的增强动作"：这一轮已经成功发出去了，压缩不该占用用户等待时间。
 * 同一会话同时只跑一个摘要任务（{@code chat-summary:<sessionId>}），避免两轮并发时互相覆盖。
 *
 * <h2>正确性靠 SQL 而不是靠单飞</h2>
 * 单飞只是"尽力而为"；真正保证不回退的是写回时的 <b>指针只前进</b> 条件
 * （{@code summary_upto_msg_id <} 才更新）。慢任务晚到也不会把摘要指针拉回去，
 * 从而不会出现"摘要在 20 条外、指针却在 10"这种自相矛盾的状态。
 *
 * <h2>失败降级</h2>
 * 与长期记忆抽取一致：只记日志，绝不把异常抛给调用方。
 */
public interface ChatSummaryService {

    /**
     * 本轮装配算出的「水位信号」——摘要门控的唯一输入。
     *
     * <h2>为什么要显式传进来，而不是摘要服务自己再查一遍库</h2>
     * 判据必须与<b>被保护的那个量</b>同源：真正决定"该不该压"的是"这一轮装配装不装得下未摘要的消息"，
     * 而那只在装配阶段知道。让摘要服务自己去数候选条数，就会出现两套数（候选 vs 必留带）打架，
     * 继而是"窗口明明装得下却每轮都压"或"已经丢了消息却还说不急"。
     *
     * @param pendingMessages 摘要指针之后、还没被覆盖的消息条数（= 必留带条数）
     * @param pendingChars    这些消息的正文合计字符数（防抖下限用）
     * @param droppedLast     上一轮被预算挤掉的未摘要条数（{@code > 0} 表示已断片，最强触发信号）
     * @param estimateTokens  本轮装配后的估算 token
     * @param budgetTokens    ai.memory.max-prompt-tokens
     */
    record SummarySignal(int pendingMessages, int pendingChars, int droppedLast,
                         int estimateTokens, int budgetTokens) {

        /** 调用方没有水位信息（老接口/被禁用的测试）：退回"有候选就压"，不拦。 */
        public static SummarySignal unknown() {
            return new SummarySignal(-1, -1, 0, -1, -1);
        }

        public boolean known() {
            return pendingMessages >= 0;
        }
    }

    /**
     * 本轮落库后调用：按水位信号决定是否异步压缩，需要时把它们压进滚动摘要。
     * <p>
     * <b>不阻塞、不抛异常</b>；不需要压缩时一次库都不查。
     */
    void maybeSummarizeAsync(Long userId, Long sessionId, SummarySignal signal);

    /**
     * 老签名（没有水位信号）：等价于 {@link #maybeSummarizeAsync(Long, Long, SummarySignal)}
     * 传 {@link SummarySignal#unknown()}，即"有候选就压"。仅供无法提供装配信号的调用方使用。
     */
    default void maybeSummarizeAsync(Long userId, Long sessionId) {
        maybeSummarizeAsync(userId, sessionId, SummarySignal.unknown());
    }

    /** 当前是否有摘要任务在跑（E2E/断言用）。 */
    boolean inFlight();

    /** 排障用的只读信息（摘要长度、指针位置、是否在跑）。 */
    Map<String, String> debugInfo();
}
