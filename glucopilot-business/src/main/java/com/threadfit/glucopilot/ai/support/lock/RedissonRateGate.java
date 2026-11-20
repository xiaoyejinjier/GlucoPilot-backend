package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * Redisson 限速实现：令牌桶，多实例共享。
 *
 * <h2>为什么不自己实现固定窗口</h2>
 * 固定窗口在边界会有"临界双倍"：窗口末秒发满 20 次、窗口首秒再发 20 次，两秒内放行 40 次。
 * 对"每分钟 20 次"这种成本闸，用户能明显感知到这种抖动（突然连续成功、然后突然被卡）。
 * Redisson 的 {@code RRateLimiter} 是令牌桶，天然把速率摊平。
 *
 * <h2>阈值变更的坑</h2>
 * {@code trySetRate} 只在键不存在时生效；把 {@code ai.concurrency.chat-per-minute} 从 20 调到 30
 * 之后老键还按 20 走。改阈值必须删掉 {@code ai:rate:chat:*}，这一点已写进 application.yaml 的注释里。
 */
public class RedissonRateGate extends RedissonSupport implements AiRateGate {

    private final RedissonClient client;

    private final InMemoryRateGate fallback;

    public RedissonRateGate(RedissonClient client, AiConcurrencyProperties properties, InMemoryRateGate fallback) {
        super(properties);
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public boolean tryAcquire(String key, int permits, Duration interval) {
        int capacity = Math.max(permits, 1);
        long intervalMillis = Math.max(interval.toMillis(), 1000L);
        try {
            RRateLimiter limiter = client.getRateLimiter(key);
            limiter.trySetRate(RateType.OVERALL, capacity, intervalMillis, RateIntervalUnit.MILLISECONDS);
            // 一次请求消耗一个令牌；"每次问答算一次"的口径在这里，而不是在调用方
            return limiter.tryAcquire(1);
        } catch (RuntimeException e) {
            if (shouldDegrade("rate.acquire", e)) {
                return fallback.tryAcquire(key, permits, interval);
            }
            throw e;
        }
    }
}
