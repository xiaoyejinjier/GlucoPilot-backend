package com.threadfit.glucopilot.ai.support.lock;

import java.time.Duration;

/**
 * 分布式限速（令牌桶）。用于"每个用户每分钟最多 N 次"这类累计型约束。
 *
 * <h2>为什么限速与互斥要分开</h2>
 * 互斥（{@link AiLeaseGuard}）管的是"同一件事不许同时做两份"，它的键是"哪一轮"；
 * 限速管的是"这个人在一段时间里做了多少次"，它的键是"哪个人"。
 * 用户开 5 个标签页发 5 条不同消息时，5 个互斥键互不冲突，只有限速能拦住。
 *
 * <h2>为什么用令牌桶而不是计数器</h2>
 * 固定窗口计数器有"临界双倍"问题：59 秒打 20 次、61 秒再打 20 次，两秒内就是 40 次。
 * 令牌桶按时间匀速回填，能表达"平均速率"，也更符合"防连点"的直觉。
 */
public interface AiRateGate {

    /**
     * 尝试从 {@code key} 的桶里取 {@code permits} 个令牌。
     *
     * @param key      业务键（含用户维度）
     * @param permits  桶容量（每个窗口允许的次数）
     * @param interval 窗口长度，例如 60 秒
     * @return 取到返回 {@code true}；桶空返回 {@code false}（调用方回 429）
     */
    boolean tryAcquire(String key, int permits, Duration interval);
}
