package com.threadfit.glucopilot.common.analysis;

import com.threadfit.glucopilot.common.util.JsonUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 餐食识别快照存储：识别结果 → Redis（{@code meal:analysis:snap:{userId}:{token}}，TTL 30 分钟）。
 *
 * <p><b>失败语义与 {@code IdempotencyStore} 刻意相反</b>：识别与保存是主功能，快照只是「低置信门禁」
 * 的增强项，因此 {@link #put} / {@link #get} 遇到任何 Redis 异常一律吞掉（返回 {@code false} /
 * {@link Optional#empty()}）并打 WARN，<b>不得向上抛</b>。缓存故障拖垮 AI 识别是最典型的事故模式；
 * 后来者若为了「统一异常处理」把这里改成抛 50000，等于把增强项变成单点故障。
 *
 * <p>与 {@code IdempotencyStore} 的另一处差异：本轮（2b-3b1）只写不读，保存链路尚未消费该 token。
 */
@Slf4j
@Component
public class MealAnalysisSnapshotStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "meal:analysis:snap:";

    private final StringRedisTemplate redis;
    private final JsonUtils json;

    public MealAnalysisSnapshotStore(StringRedisTemplate redis, JsonUtils json) {
        this.redis = redis;
        this.json = json;
    }

    /** 统一 key 规则：meal:analysis:snap:{userId}:{token}。 */
    public static String key(Long userId, String token) {
        return KEY_PREFIX + userId + ":" + token;
    }

    /**
     * 日志脱敏：只暴露前 6 位。任何涉及 token 的日志（本类与调用方）都必须走这里，
     * 不允许把完整 token 打进日志——它足以换取一次识别快照。
     */
    public static String maskToken(String token) {
        if (token == null || token.length() < 6) return "***";
        return token.substring(0, 6) + "…";
    }

    /**
     * 写入快照。任何异常（连接失败、超时、序列化失败）都不外抛，返回 {@code false} 表示
     * 「本次识别没有可用的分析令牌」，调用方应把 analysisToken 置空而不是让识别失败。
     */
    public boolean put(Long userId, String token, Snapshot snapshot) {
        try {
            redis.opsForValue().set(key(userId, token), json.toJson(snapshot), TTL);
            return true;
        } catch (RuntimeException ex) {
            log.warn("餐食识别快照写入失败，本次识别不返回 analysisToken：userId={} token={} err={}",
                    userId, maskToken(token), ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 读取快照。缺失、已过期、Redis 异常一律返回 {@link Optional#empty()}，由调用方按「无 token」
     * 降级处理（3b2 起：走旧客户端 fallback，不拒绝保存）。
     */
    public Optional<Snapshot> get(Long userId, String token) {
        try {
            String raw = redis.opsForValue().get(key(userId, token));
            if (raw == null || raw.isBlank()) return Optional.empty();
            return Optional.ofNullable(json.fromJson(raw, Snapshot.class));
        } catch (RuntimeException ex) {
            log.warn("餐食识别快照读取失败，按无 token 处理：userId={} token={} err={}",
                    userId, maskToken(token), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 识别快照：只保留「低置信拦截要判的」与「保存要对齐的」字段，不存整份识别响应。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Snapshot {
        private Long userId;
        private Long mediaId;
        /** 仅留痕，不参与校验（用户识别后改餐次是合理操作）。 */
        private String nodeCode;
        /** epoch millis，用于排查与观察 TTL 是否对齐。 */
        private Long issuedAt;
        private String model;
        private List<SnapshotItem> items;
    }

    /**
     * 单条明细快照。
     * <p>{@code libraryFoodId} / {@code foodId} 保持识别响应里的字符串形态，不做类型转换，
     * 避免写快照时引入解析失败分支；3b2 匹配时再与保存请求的数值型 id 统一。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotItem {
        private String foodName;
        /** 所属菜品名（食材级拆分后的分组名）；历史快照没有这个字段，读出来是 null。 */
        private String dishName;
        /** MAIN / SEASONING；历史快照为 null。 */
        private String role;
        /** 烹饪方式；历史快照为 null。 */
        private String cookMethod;
        private String foodId;
        private String libraryFoodId;
        private Integer categoryCode;
        private BigDecimal giValue;
        private String nutritionSource;
        /** AVAILABLE|PENDING_REVIEW|UNRESOLVED —— 3b2 低置信判定的权威来源。 */
        private String libraryStatus;
        private Integer confidence;

        /**
         * 兼容构造器：不带三样展示字段（等价于把它们置 null）。
         *
         * <p>为什么保留：V99 之前写下的快照与既有单测都只有营养相关字段。新代码请用全参构造器——
         * 少了这三样，前端就只能平铺一堆食材名，分不出哪几样属于同一道菜。</p>
         */
        public SnapshotItem(String foodName, String foodId, String libraryFoodId, Integer categoryCode,
                            BigDecimal giValue, String nutritionSource, String libraryStatus, Integer confidence) {
            this(foodName, null, null, null, foodId, libraryFoodId, categoryCode, giValue,
                    nutritionSource, libraryStatus, confidence);
        }
    }
}
