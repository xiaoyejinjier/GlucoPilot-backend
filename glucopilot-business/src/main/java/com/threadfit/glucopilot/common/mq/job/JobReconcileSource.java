package com.threadfit.glucopilot.common.mq.job;

import java.util.List;

/**
 * 补扫源：某个业务域"当前还没被处理"的任务清单。
 *
 * <h2>为什么补扫要按域分开</h2>
 * 每个域的"未处理"定义完全不同（审核看 {@code ai_status=PENDING AND ai_claimed_at IS NULL}、
 * 记忆看 {@code session_status=ENDED AND upto < 最后一条消息}、OCR 看 {@code task_status=PENDING}），
 * 而且分属不同模块。用一个统一的 SQL 去凑只会得到一个谁都不准的判据；
 * 让各域各自实现、由 {@code JobReconciler} 定时轮询，边界最清楚。
 *
 * <h2>实现约定</h2>
 * {@link #pending(int)} 必须**幂等且可重入**：它会被每 5 分钟调用一次，返回的条目可能
 * 与 MQ 里在途的消息重复。真正的去重由处理器的 {@code claim} 条件更新负责，
 * 所以这里只要求"别漏"，不要求"别重"。
 */
public interface JobReconcileSource {

    /** 域名称，仅用于日志。 */
    String name();

    /**
     * 取一批待处理任务。
     *
     * @param limit 上限，实现方必须按它做 LIMIT，避免积压时一次拉爆内存
     */
    List<JobEnvelope> pending(int limit);
}
