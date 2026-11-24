package com.threadfit.glucopilot.ai.support.lock;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内计数器：单测与降级用。
 *
 * <p>降级期间额度只剩"本实例视角"，多实例会各自放行一份——这是可接受的：
 * 额度是成本闸而不是正确性约束，Redis 恢复后自动回到全局限额。
 */
public class InMemoryCounter implements AiCounter {

    /**
     * 与额度策略共用同一个时钟：{@code AiConcurrencyPolicy} 用注入的 {@link Clock} 算"今天"，
     * 计数器若仍读系统墙钟，跨天用例就会在真实日期越过固定日期后变成"已过期、永远放行"。
     * 生产里两者都是系统时钟，行为不变；单测里必须能一起钉住。
     */
    private final Clock clock;

    /** key → (value, expireAtMillis)。 */
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public InMemoryCounter() {
        this(Clock.systemDefaultZone());
    }

    public InMemoryCounter(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public long increment(String key, long delta, Instant expireAt) {
        long now = clock.millis();
        AtomicLong result = new AtomicLong();
        slots.compute(key, (k, slot) -> {
            Slot current = slot == null || slot.expireAtMillis <= now ? new Slot(0L, expireAt.toEpochMilli()) : slot;
            long value = current.value + delta;
            result.set(value);
            return new Slot(value, current.expireAtMillis);
        });
        return result.get();
    }

    @Override
    public long current(String key) {
        Slot slot = slots.get(key);
        if (slot == null || slot.expireAtMillis <= clock.millis()) {
            return 0L;
        }
        return slot.value;
    }

    private record Slot(long value, long expireAtMillis) {
    }
}
