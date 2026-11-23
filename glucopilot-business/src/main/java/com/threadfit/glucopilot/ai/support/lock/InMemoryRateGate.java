package com.threadfit.glucopilot.ai.support.lock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内限速：固定窗口计数，仅用于单测与降级。
 *
 * <h2>为什么降级后可以接受"固定窗口"</h2>
 * 固定窗口在窗口边界会有"临界双倍"（59 秒 20 次 + 61 秒 20 次 = 两秒 40 次），
 * 但降级Path 的前提是 Redis 已经不可用——此时宁可放过一点，也不要让聊天整体不可用。
 * 生产路径用 Redisson 的令牌桶（{@link RedissonRateGate}）。
 */
public class InMemoryRateGate implements AiRateGate {

    /** key → 当前窗口。 */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int permits, Duration interval) {
        int capacity = Math.max(permits, 1);
        // 进程内实现不需要 Redis 那样的"最小 1 秒"保护：这里的窗口可以精确到毫秒，
        // 调用方给什么就用什么（也让单测不必 sleep 一秒以上）
        long windowMillis = Math.max(interval.toMillis(), 1L);
        long now = System.currentTimeMillis();
        boolean[] granted = new boolean[1];
        windows.compute(key, (k, window) -> {
            long start = now - (now % windowMillis);
            Window current = window == null || window.startMillis != start
                    ? new Window(start, 0)
                    : window;
            if (current.count >= capacity) {
                return current;
            }
            granted[0] = true;
            return new Window(current.startMillis, current.count + 1);
        });
        return granted[0];
    }

    private record Window(long startMillis, int count) {
    }
}
