package com.threadfit.glucopilot.common.idempotency;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.threadfit.glucopilot.common.util.JsonUtils;

/**
 * 写接口幂等存储：X-Idempotency-Key 快照（Redis SETNX，TTL 24h）。
 *
 * <p>用法（Service 内）：
 * <pre>
 * String key = IdempotencyStore.key("media:register", userId, RequestContext.getIdempotencyKey());
 * Optional&lt;String&gt; snapshot = store.find(key);
 * if (snapshot.isPresent()) { return jsonUtils.fromJson(snapshot.get(), MediaFileVO.class); }
 * ... 执行业务 ...
 * store.saveIfAbsent(key, vo);
 * </pre>
 *
 * <p>Redis 不可用时异常向上抛（50000），前置条件：环境可用 Redis。
 */
@Component
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redisTemplate;
    private final JsonUtils jsonUtils;

    public IdempotencyStore(StringRedisTemplate redisTemplate, JsonUtils jsonUtils) {
        this.redisTemplate = redisTemplate;
        this.jsonUtils = jsonUtils;
    }

    /** 统一 key 规则：idem:{scope}:{userId}:{idempotencyKey}。 */
    public static String key(String scope, Long userId, String idempotencyKey) {
        return KEY_PREFIX + scope + ":" + userId + ":" + idempotencyKey;
    }

    /** 读取快照 JSON；无快照返回 empty。 */
    public Optional<String> find(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    /** 写入快照（序列化 snapshot 为 JSON，SETNX + TTL 24h）；key 已存在返回 false。 */
    public boolean saveIfAbsent(String key, Object snapshot) {
        String json = jsonUtils.toJson(snapshot);
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, json, TTL);
        return Boolean.TRUE.equals(ok);
    }
}
