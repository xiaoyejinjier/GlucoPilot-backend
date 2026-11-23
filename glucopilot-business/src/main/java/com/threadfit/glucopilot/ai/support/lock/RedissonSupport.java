package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Redisson 实现的共同部分：统一决定"Redis 出错时是降级还是抛出"。
 *
 * <h2>为什么降级默认开启</h2>
 * 这四类原语保护的是「成本」与「体验」：降级后退回进程内实现，等价于单实例部署时的行为，
 * 聊天仍然可用；而不降级意味着 Redis 抖一下整站不能聊天——用可用性换来的"绝不重复计费"，
 * 在还有 {@code uk_session_client_active} + 确定性 {@code client_msg_id} 兜底的前提下并不划算。
 *
 * <h2>为什么 WARN 要节流</h2>
 * Redis 整体不可用时，每个请求都会失败一次；不限流地打日志，日志会在几秒内被同样的堆栈刷满，
 * 反而把真正的第一条线索埋掉。这里按 60 秒一条输出。
 */
@Slf4j
abstract class RedissonSupport {

    private static final long WARN_INTERVAL_MILLIS = 60_000L;

    private final AiConcurrencyProperties properties;

    private final AtomicLong lastWarnAt = new AtomicLong();

    protected RedissonSupport(AiConcurrencyProperties properties) {
        this.properties = properties;
    }

    /** Redis 异常时的统一出口：降级返回 true（调用方走 fallback），否则原样抛出。 */
    protected boolean shouldDegrade(String operation, RuntimeException error) {
        if (!properties.isDegradeOnRedisError()) {
            throw error;
        }
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("Redis 不可用，AI 并发原语已退回进程内实现（{}）；多实例期间可能重复生成，"
                    + "由数据库唯一约束兜底", operation, error);
        }
        return true;
    }
}
