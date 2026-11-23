package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.Date;

/**
 * Redisson 计数器实现：{@code RAtomicLong} + 到期时刻。
 *
 * <h2>为什么要判断"是不是首次自增"</h2>
 * 额度键要在跨天时自动消失。`INCRBY` 之后再 `EXPIRE` 是两步、非原子，
 * 但如果每次都设过期时间，就变成"每次请求都把 TTL 往后推"——用户连着用一整天，
 * 这个键永远不会过期，第二天仍然按昨天的累计拒绝服务。
 * 这里只在"自增后的值恰好等于 delta"（说明本次是首次自增）时设置过期时间，
 * 把非原子窗口收窄到"键刚创建"的那一瞬间，且这一瞬间的代价只是"键多活一天"。
 *
 * <h2>为什么过期时间是绝对时刻</h2>
 * 业务口径是"次日 00:00 归零"，用 `expireAt` 而不是 `expire(86400)`：
 * 前者在 23:59 创建也只会活 1 分钟，后者会活满 24 小时，跨越两个自然日。
 */
public class RedissonCounter extends RedissonSupport implements AiCounter {

    private final RedissonClient client;

    private final InMemoryCounter fallback;

    public RedissonCounter(RedissonClient client, AiConcurrencyProperties properties, InMemoryCounter fallback) {
        super(properties);
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public long increment(String key, long delta, Instant expireAt) {
        try {
            RAtomicLong counter = client.getAtomicLong(key);
            long value = counter.addAndGet(delta);
            if (value == delta) {
                counter.expireAt(Date.from(expireAt));
            }
            return value;
        } catch (RuntimeException e) {
            if (shouldDegrade("counter.increment", e)) {
                return fallback.increment(key, delta, expireAt);
            }
            throw e;
        }
    }

    @Override
    public long current(String key) {
        try {
            return client.getAtomicLong(key).get();
        } catch (RuntimeException e) {
            if (shouldDegrade("counter.current", e)) {
                return fallback.current(key);
            }
            throw e;
        }
    }
}
