package com.threadfit.glucopilot.ai.support.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内小缓存：单测与降级用。
 *
 * <p>与 Redis 实现的契约完全相同（{@code putIfAbsent} 的原子性由
 * {@link ConcurrentHashMap#compute} 保证），因此可以直接替换而不改调用方。
 */
public class InMemoryCacheStore implements AiCacheStore {

    /** mapKey → (field → entry)。 */
    private final Map<String, Map<String, Entry>> tables = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String mapKey, String field) {
        Map<String, Entry> table = tables.get(mapKey);
        if (table == null) {
            return Optional.empty();
        }
        Entry entry = table.get(field);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expireAtMillis <= System.currentTimeMillis()) {
            table.remove(field, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    @Override
    public boolean putIfAbsent(String mapKey, String field, String value, Duration ttl) {
        long expireAt = System.currentTimeMillis() + Math.max(ttl.toMillis(), 1L);
        boolean[] written = new boolean[1];
        tables.compute(mapKey, (k, table) -> {
            Map<String, Entry> current = table == null ? new ConcurrentHashMap<>() : table;
            Entry existing = current.get(field);
            boolean alive = existing != null && existing.expireAtMillis > System.currentTimeMillis();
            if (alive) {
                return current;
            }
            current.put(field, new Entry(value, expireAt));
            written[0] = true;
            return current;
        });
        return written[0];
    }

    @Override
    public void put(String mapKey, String field, String value, Duration ttl) {
        long expireAt = System.currentTimeMillis() + Math.max(ttl.toMillis(), 1L);
        tables.compute(mapKey, (k, table) -> {
            Map<String, Entry> current = table == null ? new ConcurrentHashMap<>() : table;
            current.put(field, new Entry(value, expireAt));
            return current;
        });
    }

    @Override
    public void delete(String mapKey, String field) {
        Map<String, Entry> table = tables.get(mapKey);
        if (table != null) {
            table.remove(field);
        }
    }

    @Override
    public void deleteIf(String mapKey, java.util.function.Predicate<String> fieldPredicate) {
        Map<String, Entry> table = tables.get(mapKey);
        if (table != null) {
            table.keySet().removeIf(fieldPredicate);
        }
    }

    @Override
    public void deleteAll(String mapKey) {
        tables.remove(mapKey);
    }

    private record Entry(String value, long expireAtMillis) {
    }
}
