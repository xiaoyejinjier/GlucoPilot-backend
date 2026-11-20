package com.threadfit.glucopilot.ai.support.lock;

import java.time.Instant;

/**
 * 分布式计数器，只用于"每日额度"这类按天归零的成本闸。
 *
 * <h2>为什么额度不能用 {@link AiCacheStore} 表达</h2>
 * 缓存是"有则读、无则写"，而额度需要<b>原子自增</b>并返回自增后的值：
 * 两个并发请求同时读到 199 再各自 +1，就会放行 201 次。原子自增只有 Redis 的
 * {@code INCRBY}（Redisson {@code RAtomicLong}）能给，不能被"读改写"替代。
 *
 * <h2>为什么带过期时间</h2>
 * 额度是"每日"的，键里带日期（{@code :20261120}）就已经天然分桶，过期时间只是防止
 * 键无限堆积（历史日期的键永远不再被读）。到期时刻取次日 00:00，与业务口径一致。
 */
public interface AiCounter {

    /**
     * 原子自增并返回自增后的值；键不存在时从 0 开始。
     *
     * @param expireAt 该键的过期时刻（已存在时只在首次创建时设置，避免每次请求都刷新 TTL）
     */
    long increment(String key, long delta, Instant expireAt);

    /** 当前值；键不存在或已过期返回 0。 */
    long current(String key);
}
