package com.threadfit.glucopilot.community.ranking.config;

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 社区热榜：Redis ZSET 缓存与重建参数；打分公式见 Mapper XML（方案 C）。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "community.hot-ranking")
public class CommunityHotRankingProperties {
    private boolean enabled = true;
    /** 正式榜 key。 */
    private String key = "community:hot:articles";
    /** 物理 TTL：key 保留时长，应明显长于 logicalTtl，便于逻辑过期后续命。 */
    private Duration cacheTtl = Duration.ofMinutes(30);
    /** 逻辑新鲜度：超过后仍可读旧榜，但触发抢锁重建。 */
    private Duration logicalTtl = Duration.ofMinutes(2);
    private Duration lockTtl = Duration.ofSeconds(60);
    /** 定时重建间隔（毫秒），默认 2 分钟。 */
    private long rebuildIntervalMs = 120_000L;
    /**
     * 已废弃：不再在请求线程 sleep 等待锁。保留配置项以免旧部署报错；校验仍生效。
     */
    private long lockWaitMs = 300L;
    /** Redis 降级单飞等待 SQL 的超时。 */
    private long fallbackTimeoutMs = 800L;
    /** 进程内短快照 TTL，吸收同实例重复 miss / 降级。 */
    private long localSnapshotTtlMs = 2_000L;
    /** 写入 ZSET 的候选上限（重算 TopN）。 */
    private int rebuildLimit = 200;
    /** 读榜时从 ZSET 多取再按可见性过滤。 */
    private int readBuffer = 50;

    @PostConstruct
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("community.hot-ranking.key 不能为空");
        }
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("community.hot-ranking.cache-ttl 必须为正");
        }
        if (logicalTtl == null || logicalTtl.isZero() || logicalTtl.isNegative()) {
            throw new IllegalArgumentException("community.hot-ranking.logical-ttl 必须为正");
        }
        if (logicalTtl.compareTo(cacheTtl) > 0) {
            throw new IllegalArgumentException(
                    "community.hot-ranking.logical-ttl 不能大于 cache-ttl");
        }
        if (lockTtl == null || lockTtl.isZero() || lockTtl.isNegative()) {
            throw new IllegalArgumentException("community.hot-ranking.lock-ttl 必须为正");
        }
        if (rebuildIntervalMs < 10_000L || rebuildIntervalMs > 3_600_000L) {
            throw new IllegalArgumentException(
                    "community.hot-ranking.rebuild-interval-ms 须在 10s～1h");
        }
        if (lockWaitMs < 0L || lockWaitMs > 5_000L) {
            throw new IllegalArgumentException("community.hot-ranking.lock-wait-ms 须在 0～5s");
        }
        if (fallbackTimeoutMs < 100L || fallbackTimeoutMs > 10_000L) {
            throw new IllegalArgumentException(
                    "community.hot-ranking.fallback-timeout-ms 须在 100ms～10s");
        }
        if (localSnapshotTtlMs < 0L || localSnapshotTtlMs > 60_000L) {
            throw new IllegalArgumentException(
                    "community.hot-ranking.local-snapshot-ttl-ms 须在 0～60s");
        }
        if (rebuildLimit < 10 || rebuildLimit > 1000) {
            throw new IllegalArgumentException("community.hot-ranking.rebuild-limit 须在 10～1000");
        }
        if (readBuffer < 10 || readBuffer > 200) {
            throw new IllegalArgumentException("community.hot-ranking.read-buffer 须在 10～200");
        }
    }

    public String lockKey() {
        return key + ":lock";
    }

    public String buildKey(String suffix) {
        return key + ":build:" + suffix;
    }

    public String metaKey() {
        return key + ":meta";
    }
}
