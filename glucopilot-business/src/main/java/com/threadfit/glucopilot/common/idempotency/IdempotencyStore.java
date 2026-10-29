package com.threadfit.glucopilot.common.idempotency;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.threadfit.glucopilot.common.util.JsonUtils;

/**
 * 写接口幂等存储：X-Idempotency-Key 快照（Redis SETNX，TTL 24h）。
 *
 * <p>正确并发用法（先占锁再落库，避免「先查后写」竞态产生重复记录）：
 * <pre>
 * String key = IdempotencyStore.key("media:register", userId, RequestContext.getIdempotencyKey());
 * Claim claim = store.claim(key);
 * if (claim instanceof Claim.Replay r) { return json.fromJson(r.json(), VO.class); }
 * if (claim instanceof Claim.InFlight) { throw conflict; }
 * try {
 *   VO vo = doWork();
 *   store.complete(key, vo);
 *   return vo;
 * } catch (RuntimeException ex) {
 *   store.release(key);
 *   throw ex;
 * }
 * </pre>
 *
 * <p>Redis 不可用时异常向上抛（50000），前置条件：环境可用 Redis。
 */
@Component
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idem:";
    /** 占位标记：已有请求在执行，尚无最终快照。 */
    static final String PENDING = "__pending__";

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

    /**
     * 抢占执行权或回放已完成快照。
     * <ul>
     *   <li>{@link Claim.Execute}：本请求应执行业务并 {@link #complete}</li>
     *   <li>{@link Claim.Replay}：直接回放 JSON 快照</li>
     *   <li>{@link Claim.InFlight}：他请求正在处理，调用方应返回冲突或稍后重试</li>
     * </ul>
     */
    public Claim claim(String key) {
        String existing = redisTemplate.opsForValue().get(key);
        if (existing != null) {
            if (PENDING.equals(existing)) {
                return Claim.InFlight.INSTANCE;
            }
            return new Claim.Replay(existing);
        }
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, PENDING, TTL);
        if (Boolean.TRUE.equals(ok)) {
            return Claim.Execute.INSTANCE;
        }
        String again = redisTemplate.opsForValue().get(key);
        if (again != null && !PENDING.equals(again)) {
            return new Claim.Replay(again);
        }
        return Claim.InFlight.INSTANCE;
    }

    /** 业务成功后覆盖 pending 为最终 JSON 快照。 */
    public void complete(String key, Object snapshot) {
        String json = jsonUtils.toJson(snapshot);
        redisTemplate.opsForValue().set(key, json, TTL);
    }

    /** 业务失败时释放占位，允许客户端用同一幂等键重试。 */
    public void release(String key) {
        String current = redisTemplate.opsForValue().get(key);
        if (PENDING.equals(current)) {
            redisTemplate.delete(key);
        }
    }

    /** 读取快照 JSON；无快照或仍为 pending 返回 empty。 */
    public Optional<String> find(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || PENDING.equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * 写入快照（序列化 snapshot 为 JSON，SETNX + TTL 24h）；key 已存在返回 false。
     * @deprecated 易与「先落库后 SETNX」组合产生并发重复；新代码请用 {@link #claim}/{@link #complete}
     */
    @Deprecated
    public boolean saveIfAbsent(String key, Object snapshot) {
        String json = jsonUtils.toJson(snapshot);
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, json, TTL);
        return Boolean.TRUE.equals(ok);
    }

    public sealed interface Claim permits Claim.Execute, Claim.Replay, Claim.InFlight {
        record Execute() implements Claim {
            static final Execute INSTANCE = new Execute();
        }

        record Replay(String json) implements Claim {
        }

        record InFlight() implements Claim {
            static final InFlight INSTANCE = new InFlight();
        }
    }
}
