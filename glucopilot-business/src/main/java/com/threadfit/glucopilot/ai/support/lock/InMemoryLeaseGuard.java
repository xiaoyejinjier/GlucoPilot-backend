package com.threadfit.glucopilot.ai.support.lock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内租约实现：单实例可用，多实例失效。
 *
 * <h2>它为什么还要存在</h2>
 * <ul>
 *   <li><b>单测</b>：1186 条测试里绝大多数不关心 Redis，也不该为了一个锁去起容器；</li>
 *   <li><b>降级</b>：Redis 不可用时退回这里，等价于"今天单实例部署"的行为，
 *       聊天不中断（重复生成由数据库唯一约束兜底）；</li>
 *   <li><b>对照</b>：两套实现共用同一组测试（{@code AiLeaseGuardContract}），
 *       可以证明"分布式实现没有偷偷改变语义"。</li>
 * </ul>
 *
 * <h2>为什么过期用 {@link System#nanoTime()}</h2>
 * 墙钟会被 NTP 调整甚至手工改时间，租约长度只有几十秒，一次向前跳变就能让所有占位瞬间"过期"，
 * 或全部变相永不过期。{@code nanoTime} 单调递增，只用于计算"过了多久"，与绝对时间无关。
 */
public class InMemoryLeaseGuard implements AiLeaseGuard {

    /** key → (token → 过期时刻 nanos)。 */
    private final Map<String, Map<String, Long>> held = new ConcurrentHashMap<>();

    private final AtomicLong tokenSeq = new AtomicLong();

    @Override
    public String tryAcquire(String key, int permits, Duration lease) {
        int capacity = Math.max(permits, 1);
        long now = System.nanoTime();
        long expireAt = now + lease.toNanos();
        String[] granted = new String[1];
        held.compute(key, (k, tokens) -> {
            Map<String, Long> current = tokens == null ? new ConcurrentHashMap<>() : tokens;
            current.entrySet().removeIf(e -> e.getValue() <= now);
            if (current.size() >= capacity) {
                return current.isEmpty() ? null : current;
            }
            String token = "mem-" + tokenSeq.incrementAndGet();
            current.put(token, expireAt);
            granted[0] = token;
            return current;
        });
        return granted[0];
    }

    @Override
    public boolean release(String key, String token) {
        if (token == null) {
            return false;
        }
        boolean[] removed = new boolean[1];
        held.computeIfPresent(key, (k, tokens) -> {
            removed[0] = tokens.remove(token) != null;
            return tokens.isEmpty() ? null : tokens;
        });
        return removed[0];
    }

    @Override
    public int held(String key) {
        Map<String, Long> tokens = held.get(key);
        if (tokens == null) {
            return 0;
        }
        long now = System.nanoTime();
        tokens.entrySet().removeIf(e -> e.getValue() <= now);
        return tokens.size();
    }
}
