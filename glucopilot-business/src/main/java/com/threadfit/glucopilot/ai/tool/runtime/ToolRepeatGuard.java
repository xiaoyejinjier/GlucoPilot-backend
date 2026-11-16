package com.threadfit.glucopilot.ai.tool.runtime;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 同一次会话里「同一个工具、同一份入参」的重复调用拦截。
 *
 * <p><b>为什么需要它</b>：模型的工具调用是逐轮决策的，它看不到「我上一轮已经查过这件事」——
 * 尤其当窗口滑走、结论只留在上下文里时，重复调用很容易发生。重复调用不只是浪费一次模型往返，
 * 还会在工具的记账表里留下两条一样的记录，让「这个用户今天查了几次血糖」这类统计失真。
 *
 * <p><b>判据刻意很窄</b>：会话 + 工具名 + <b>入参原文</b>三者都相同才算重复。
 * 入参不同（比如把 days 从 7 改成 30）一定放行——那本来就是新的问题。
 * 结果只缓 30 分钟：数据是会变的，今天下午和晚上问同一句「今天血糖怎么样」应该拿到不同的答案。
 *
 * <p><b>只做去重，不做限流</b>：调用次数上限仍由框架的 {@code spring.ai.tools.limits.*} 负责，
 * 这里若再写一套计数器，两处上限会互相打架（见 {@link AiToolRegistry} 的约定）。
 */
@Slf4j
@Component
public class ToolRepeatGuard {

    /** 结果缓存时长：超过就重新执行。 */
    static final Duration TTL = Duration.ofMinutes(30);

    /** 每个会话最多记多少条（LRU 淘汰）。 */
    static final int MAX_ENTRIES_PER_SESSION = 16;

    /** 最多同时记多少个会话（LRU 淘汰，防止内存随用户数无限增长）。 */
    static final int MAX_SESSIONS = 512;

    /** 超过这个长度的结果不缓存：大结果复制一份内存不划算，重复执行也贵不到哪去。 */
    static final int MAX_CACHEABLE_CHARS = 8_000;

    /** 缓存时长（可注入，测试要用毫秒级的 TTL 验证过期）。 */
    private final Duration ttl;

    public ToolRepeatGuard() {
        this(TTL);
    }

    ToolRepeatGuard(Duration ttl) {
        this.ttl = ttl;
    }

    private final Map<Long, SessionCache> sessions =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, SessionCache> eldest) {
                    return size() > MAX_SESSIONS;
                }
            };

    /** 去重键：会话 + 工具名 + 规范化后的入参。 */
    public record Key(long sessionId, String toolName, String input) {

        /** 会话内的键：会话 id 已经作为外层 map 的键，这里不再重复。 */
        String cacheKey() {
            return toolName + '\u0000' + input;
        }
    }

    /**
     * 命中上次结果则返回它；未命中或已过期返回空。
     *
     * @param key 去重键
     * @return 上次的结果（可能为空列表以外的任何字符串）
     */
    public Optional<String> recall(Key key) {
        synchronized (sessions) {
            SessionCache cache = sessions.get(key.sessionId());
            if (cache == null) {
                return Optional.empty();
            }
            Entry entry = cache.get(key.cacheKey());
            if (entry == null) {
                return Optional.empty();
            }
            if (System.nanoTime() - entry.savedAtNanos() > ttl.toNanos()) {
                cache.remove(key.cacheKey());
                return Optional.empty();
            }
            return Optional.of(entry.result());
        }
    }

    /**
     * 记住这次结果，供同会话的后续调用复用。
     *
     * @param key 去重键
     * @param result 工具返回的原文
     */
    public void remember(Key key, String result) {
        if (result == null || result.length() > MAX_CACHEABLE_CHARS) {
            return;
        }
        synchronized (sessions) {
            sessions.computeIfAbsent(key.sessionId(), id -> new SessionCache())
                    .put(key.cacheKey(), new Entry(result, System.nanoTime()));
        }
    }

    /** 清空某会话的缓存（会话结束时调用；未接入也不影响正确性，只是多留一会儿内存）。 */
    public void forget(long sessionId) {
        synchronized (sessions) {
            sessions.remove(sessionId);
        }
    }

    /** 当前缓存的会话数（测试与观测用）。 */
    public int sessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    /** 某个会话缓存的条目数（测试用）。 */
    public int entryCount(long sessionId) {
        synchronized (sessions) {
            SessionCache cache = sessions.get(sessionId);
            return cache == null ? 0 : cache.size();
        }
    }

    /** 淘汰过期条目（不必定时调用：recall 命中过期即删；这里给测试与运维留一个显式入口）。 */
    public void evictExpired() {
        long now = System.nanoTime();
        long ttlNanos = ttl.toNanos();
        synchronized (sessions) {
            Iterator<Map.Entry<Long, SessionCache>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                SessionCache cache = it.next().getValue();
                cache.entries.values().removeIf(entry -> now - entry.savedAtNanos() > ttlNanos);
                if (cache.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    private record Entry(String result, long savedAtNanos) {
    }

    /** 单个会话的 LRU 缓存。 */
    private static final class SessionCache {

        private final LinkedHashMap<String, Entry> entries =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                        return size() > MAX_ENTRIES_PER_SESSION;
                    }
                };

        Entry get(String cacheKey) {
            return entries.get(cacheKey);
        }

        void put(String cacheKey, Entry entry) {
            entries.put(cacheKey, entry);
        }

        void remove(String cacheKey) {
            entries.remove(cacheKey);
        }

        int size() {
            return entries.size();
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }
    }
}
