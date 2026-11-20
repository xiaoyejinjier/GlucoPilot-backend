package com.threadfit.glucopilot.ai.support.lock;

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

    /** key → (value, expireAtMillis)。 */
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, long delta, Instant expireAt) {
        long now = System.currentTimeMillis();
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
        if (slot == null || slot.expireAtMillis <= System.currentTimeMillis()) {
            return 0L;
        }
        return slot.value;
    }

    private record Slot(long value, long expireAtMillis) {
    }
}
