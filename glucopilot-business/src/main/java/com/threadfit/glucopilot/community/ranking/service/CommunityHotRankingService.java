package com.threadfit.glucopilot.community.ranking.service;

import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.article.service.CommunityPublicationService;
import com.threadfit.glucopilot.community.ranking.config.CommunityHotRankingProperties;
import com.threadfit.glucopilot.community.ranking.dto.HotArticleScore;
import com.threadfit.glucopilot.community.ranking.dto.HotRankingDtos;
import com.threadfit.glucopilot.community.ranking.mapper.CommunityHotRankingMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 社区热榜：MySQL 方案 C 全量重算为权威，Redis ZSET 缓存。
 *
 * <p>穿透（空值命中）、击穿（逻辑 TTL + 异步抢锁重建 + 吃旧）、雪崩（单飞 + 进程快照）； 请求线程不
 * sleep、不在逻辑过期路径同步全量 SQL；灌装一次 IN 批量可读查询。
 */
@Service
public class CommunityHotRankingService {
    private static final Logger log = LoggerFactory.getLogger(CommunityHotRankingService.class);
    private static final int DEFAULT_LIMIT = 10;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String EMPTY_MEMBER = "__empty__";

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
        RELEASE_LOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "return redis.call('del', KEYS[1]) else return 0 end");
    }

    private final CommunityHotRankingMapper mapper;
    private final CommunityHotRankingProperties properties;
    private final StringRedisTemplate redis;
    private final CommunityPublicationService publications;
    private final Executor hotRankingExecutor;

    /** 进程内短快照：同实例吸收重复 miss / 降级。 */
    private final AtomicReference<RankSnapshot> localSnapshot = new AtomicReference<>();

    /** Redis 宕机时 MySQL 单飞。 */
    private final AtomicReference<CompletableFuture<List<HotArticleScore>>> sqlFlight =
            new AtomicReference<>();

    public CommunityHotRankingService(
            CommunityHotRankingMapper mapper,
            CommunityHotRankingProperties properties,
            StringRedisTemplate redis,
            CommunityPublicationService publications,
            @Qualifier("hotRankingExecutor") Executor hotRankingExecutor) {
        this.mapper = mapper;
        this.properties = properties;
        this.redis = redis;
        this.publications = publications;
        this.hotRankingExecutor = hotRankingExecutor;
    }

    /** 方案 C 公式（单测与文档对齐）；hours 为发布至今天数小时差（≥0）。 */
    static double schemeCScore(long likes, long collects, long comments, long views, long hours) {
        double raw =
                3.0 * Math.max(likes, 0)
                        + 5.0 * Math.max(collects, 0)
                        + 4.0 * Math.max(comments, 0)
                        + 1.0 * Math.max(views, 0);
        double denom = Math.pow(Math.max(hours, 0) + 12.0, 1.5);
        return Math.log(1.0 + raw) / denom;
    }

    public HotRankingDtos.HotArticlesResponse hotArticles(long viewer, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), DEFAULT_LIMIT);
        RankLoad load = loadRankedIds(size);
        List<PublicationDtos.Article> records = hydrate(viewer, load.rows(), size);
        LocalDateTime generatedAt =
                load.generatedAtMs() > 0
                        ? LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(load.generatedAtMs()), ZONE)
                        : LocalDateTime.now(ZONE);
        return new HotRankingDtos.HotArticlesResponse(records, generatedAt, records.size());
    }

    private RankLoad loadRankedIds(int need) {
        int buffer = Math.max(properties.getReadBuffer(), need);
        if (!properties.isEnabled()) {
            return new RankLoad(loadFromMysqlSingleFlight(buffer), System.currentTimeMillis());
        }
        try {
            CacheView view = readCache(buffer);
            Meta meta = readMeta();
            boolean logicallyFresh = meta != null && !meta.logicalExpired();

            if (view.status() == CacheStatus.HIT || view.status() == CacheStatus.EMPTY) {
                rememberSnapshot(view.rows(), meta != null ? meta.generatedAtMs() : 0L);
                if (!logicallyFresh) {
                    // 逻辑过期：请求线程只吃旧，异步抢锁重建
                    triggerAsyncRebuild();
                }
                return new RankLoad(view.rows(), meta != null ? meta.generatedAtMs() : 0L);
            }

            // MISS：异步抢锁重建；本请求立即吃进程快照或空，不 sleep、不同步全量 SQL
            triggerAsyncRebuild();
            RankSnapshot snap = localSnapshot.get();
            if (snap != null && snap.usable()) {
                return new RankLoad(snap.rows(), snap.generatedAtMs());
            }
            return new RankLoad(List.of(), System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("社区热榜 Redis 不可用，单飞降级 MySQL: {}", e.toString());
            return new RankLoad(loadFromMysqlSingleFlight(buffer), System.currentTimeMillis());
        }
    }

    void triggerAsyncRebuild() {
        try {
            hotRankingExecutor.execute(
                    () -> {
                        try {
                            rebuildCache();
                        } catch (Exception e) {
                            log.warn("社区热榜异步重建失败: {}", e.toString());
                        }
                    });
        } catch (RuntimeException e) {
            log.debug("社区热榜异步重建提交失败: {}", e.toString());
        }
    }

    private CacheView readCache(int buffer) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet()
                        .reverseRangeWithScores(properties.getKey(), 0, buffer - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return CacheView.miss();
        }
        List<HotArticleScore> rows = new ArrayList<>(tuples.size());
        boolean sawEmpty = false;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            if (member == null) {
                continue;
            }
            if (EMPTY_MEMBER.equals(member)) {
                sawEmpty = true;
                continue;
            }
            long id;
            try {
                id = Long.parseLong(member);
            } catch (NumberFormatException ignored) {
                continue;
            }
            double score = tuple.getScore() == null ? 0 : tuple.getScore();
            rows.add(new HotArticleScore(id, score));
        }
        if (!rows.isEmpty()) {
            return CacheView.hit(rows);
        }
        if (sawEmpty) {
            return CacheView.empty();
        }
        return CacheView.empty();
    }

    private List<PublicationDtos.Article> hydrate(
            long viewer, List<HotArticleScore> ranked, int limit) {
        if (ranked == null || ranked.isEmpty() || limit <= 0) {
            return List.of();
        }
        int maxScan = Math.max(properties.getReadBuffer(), limit);
        List<Long> orderedIds = new ArrayList<>(Math.min(maxScan, ranked.size()));
        for (HotArticleScore row : ranked) {
            if (orderedIds.size() >= maxScan) {
                break;
            }
            orderedIds.add(row.newsId());
        }
        return publications.listForHotRanking(viewer, orderedIds, limit);
    }

    private Meta readMeta() {
        String raw = redis.opsForValue().get(properties.metaKey());
        return Meta.parse(raw);
    }

    private void rememberSnapshot(List<HotArticleScore> rows, long generatedAtMs) {
        long ttl = properties.getLocalSnapshotTtlMs();
        if (ttl <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        localSnapshot.set(
                new RankSnapshot(
                        List.copyOf(rows),
                        generatedAtMs > 0 ? generatedAtMs : now,
                        now + ttl));
    }

    private List<HotArticleScore> loadFromMysqlSingleFlight(int buffer) {
        RankSnapshot snap = localSnapshot.get();
        if (snap != null && snap.usable()) {
            return snap.rows();
        }
        CompletableFuture<List<HotArticleScore>> inflight = sqlFlight.get();
        if (inflight != null) {
            return awaitFlight(inflight, snap);
        }
        CompletableFuture<List<HotArticleScore>> created = new CompletableFuture<>();
        if (!sqlFlight.compareAndSet(null, created)) {
            CompletableFuture<List<HotArticleScore>> won = sqlFlight.get();
            return won == null ? List.of() : awaitFlight(won, snap);
        }
        try {
            List<HotArticleScore> rows = mapper.selectTopScores(buffer);
            rememberSnapshot(rows, System.currentTimeMillis());
            created.complete(rows);
            return rows;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            if (snap != null) {
                return snap.rows();
            }
            throw e;
        } finally {
            sqlFlight.compareAndSet(created, null);
        }
    }

    private List<HotArticleScore> awaitFlight(
            CompletableFuture<List<HotArticleScore>> flight, RankSnapshot snap) {
        try {
            return flight.get(properties.getFallbackTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (snap != null) {
                return snap.rows();
            }
            return List.of();
        }
    }

    @Scheduled(fixedDelayString = "${community.hot-ranking.rebuild-interval-ms:120000}")
    public void scheduledRebuild() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            rebuildCache();
        } catch (Exception e) {
            log.warn("社区热榜定时重建失败: {}", e.toString());
        }
    }

    /** 抢锁全量重算并双 key 切换；未抢到锁则直接返回 false。仅持锁线程执行 SQL。 */
    public boolean rebuildCache() {
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean locked =
                redis.opsForValue()
                        .setIfAbsent(
                                properties.lockKey(),
                                token,
                                properties.getLockTtl().toMillis(),
                                TimeUnit.MILLISECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }
        String buildKey = properties.buildKey(UUID.randomUUID().toString().replace("-", ""));
        try {
            List<HotArticleScore> rows = mapper.selectTopScores(properties.getRebuildLimit());
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            if (!rows.isEmpty()) {
                for (HotArticleScore row : rows) {
                    tuples.add(
                            new DefaultTypedTuple<>(
                                    Long.toString(row.newsId()), row.score()));
                }
            } else {
                tuples.add(new DefaultTypedTuple<>(EMPTY_MEMBER, 0.0));
            }
            redis.opsForZSet().add(buildKey, tuples);
            redis.expire(buildKey, properties.getCacheTtl());
            redis.rename(buildKey, properties.getKey());
            redis.expire(properties.getKey(), properties.getCacheTtl());
            long now = System.currentTimeMillis();
            long logicalExpire = now + properties.getLogicalTtl().toMillis();
            redis.opsForValue()
                    .set(
                            properties.metaKey(),
                            now + "|" + logicalExpire,
                            properties.getCacheTtl());
            rememberSnapshot(rows, now);
            return true;
        } catch (RuntimeException e) {
            try {
                redis.delete(buildKey);
            } catch (Exception ignored) {
                // best-effort
            }
            throw e;
        } finally {
            releaseLock(token);
        }
    }

    /** Lua：仅当锁 value 仍是本次 token 时删除，避免误删他人锁。 */
    void releaseLock(String token) {
        try {
            redis.execute(
                    RELEASE_LOCK_SCRIPT, List.of(properties.lockKey()), token);
        } catch (Exception ignored) {
            // lock TTL 兜底
        }
    }

    enum CacheStatus {
        HIT,
        EMPTY,
        MISS
    }

    record CacheView(CacheStatus status, List<HotArticleScore> rows) {
        static CacheView hit(List<HotArticleScore> rows) {
            return new CacheView(CacheStatus.HIT, rows);
        }

        static CacheView empty() {
            return new CacheView(CacheStatus.EMPTY, List.of());
        }

        static CacheView miss() {
            return new CacheView(CacheStatus.MISS, List.of());
        }
    }

    record Meta(long generatedAtMs, long logicalExpireAtMs) {
        boolean logicalExpired() {
            return System.currentTimeMillis() >= logicalExpireAtMs;
        }

        static Meta parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                String text = raw.trim();
                int pipe = text.indexOf('|');
                if (pipe < 0) {
                    long generated = Long.parseLong(text);
                    return new Meta(generated, 0L);
                }
                long generated = Long.parseLong(text.substring(0, pipe));
                long logical = Long.parseLong(text.substring(pipe + 1));
                return new Meta(generated, logical);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    record RankSnapshot(List<HotArticleScore> rows, long generatedAtMs, long expireAtMs) {
        boolean usable() {
            return System.currentTimeMillis() < expireAtMs;
        }
    }

    record RankLoad(List<HotArticleScore> rows, long generatedAtMs) {}
}
