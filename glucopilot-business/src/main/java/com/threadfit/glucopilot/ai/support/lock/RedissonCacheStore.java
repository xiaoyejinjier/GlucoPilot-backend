package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 小缓存实现：一张 Redis Hash（{@code RMapCache}），每个字段带独立 TTL。
 *
 * <h2>为什么是 {@code RMapCache} 而不是 {@code RBucket} + 拼接键</h2>
 * 摘要/抽取的合并标记确实可以每个会话一个 {@code RBucket} 键，但工具结果缓存需要"整张表一次清空"，
 * 而 {@code RBucket} 只能逐个删、或 {@code SCAN} 前缀——共享 Redis 上 {@code SCAN} 是禁忌。
 * {@code RMapCache} 的 {@code delete()} 一次删掉整张 Hash，字段级 TTL 又满足"30 分钟过期"。
 *
 * <h2>为什么用 {@link StringCodec}</h2>
 * 存的是"已经是 JSON 的文本"，用默认的 Kryo 编解码器会多一层二进制包装，
 * 出问题时 {@code redis-cli} 里看到的是乱码；用字符串编解码器可以直接肉眼排查。
 */
public class RedissonCacheStore extends RedissonSupport implements AiCacheStore {

    private final RedissonClient client;

    private final InMemoryCacheStore fallback;

    public RedissonCacheStore(RedissonClient client, AiConcurrencyProperties properties, InMemoryCacheStore fallback) {
        super(properties);
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public Optional<String> get(String mapKey, String field) {
        try {
            return Optional.ofNullable(table(mapKey).get(field));
        } catch (RuntimeException e) {
            if (shouldDegrade("cache.get", e)) {
                return fallback.get(mapKey, field);
            }
            throw e;
        }
    }

    @Override
    public boolean putIfAbsent(String mapKey, String field, String value, Duration ttl) {
        try {
            String previous = table(mapKey)
                    .putIfAbsent(field, value, Math.max(ttl.toMillis(), 1000L), TimeUnit.MILLISECONDS);
            return previous == null;
        } catch (RuntimeException e) {
            if (shouldDegrade("cache.putIfAbsent", e)) {
                return fallback.putIfAbsent(mapKey, field, value, ttl);
            }
            throw e;
        }
    }

    @Override
    public void put(String mapKey, String field, String value, Duration ttl) {
        try {
            table(mapKey).put(field, value, Math.max(ttl.toMillis(), 1000L), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            if (shouldDegrade("cache.put", e)) {
                fallback.put(mapKey, field, value, ttl);
                return;
            }
            throw e;
        }
    }

    @Override
    public void delete(String mapKey, String field) {
        try {
            table(mapKey).remove(field);
        } catch (RuntimeException e) {
            if (shouldDegrade("cache.delete", e)) {
                fallback.delete(mapKey, field);
                return;
            }
            throw e;
        }
    }

    @Override
    public void deleteIf(String mapKey, java.util.function.Predicate<String> fieldPredicate) {
        try {
            RMapCache<String, String> table = table(mapKey);
            // 先复制 keySet 再删：RMapCache.keySet() 是实时的，边遍历边删会抛 ConcurrentModificationException
            for (String field : new java.util.ArrayList<>(table.keySet())) {
                if (fieldPredicate.test(field)) {
                    table.remove(field);
                }
            }
        } catch (RuntimeException e) {
            if (shouldDegrade("cache.deleteIf", e)) {
                fallback.deleteIf(mapKey, fieldPredicate);
                return;
            }
            throw e;
        }
    }

    @Override
    public void deleteAll(String mapKey) {
        try {
            // 删整张 Hash：{@code RMap.delete()} 是"删这个 key"，不是逐字段删
            table(mapKey).delete();
        } catch (RuntimeException e) {
            if (shouldDegrade("cache.deleteAll", e)) {
                fallback.deleteAll(mapKey);
                return;
            }
            throw e;
        }
    }

    private RMapCache<String, String> table(String mapKey) {
        return client.getMapCache(mapKey, StringCodec.INSTANCE);
    }
}
