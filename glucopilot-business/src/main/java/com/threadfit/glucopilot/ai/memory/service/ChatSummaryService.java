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
     * 本轮落库后调用：若已有消息落在窗口之外，异步把它们压缩进滚动摘要。
     * <p>
     * <b>不阻塞、不抛异常</b>；不需要压缩时一次库都不查。
     */
    void maybeSummarizeAsync(Long userId, Long sessionId);

    /** 当前是否有摘要任务在跑（E2E/断言用）。 */
    boolean inFlight();

    /** 排障用的只读信息（摘要长度、指针位置、是否在跑）。 */
    Map<String, String> debugInfo();
}
