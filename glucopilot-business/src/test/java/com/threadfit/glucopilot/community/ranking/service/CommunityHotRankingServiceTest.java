package com.threadfit.glucopilot.community.ranking.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.article.service.CommunityPublicationService;
import com.threadfit.glucopilot.community.ranking.config.CommunityHotRankingProperties;
import com.threadfit.glucopilot.community.ranking.dto.HotArticleScore;
import com.threadfit.glucopilot.community.ranking.dto.HotRankingDtos;
import com.threadfit.glucopilot.community.ranking.mapper.CommunityHotRankingMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class CommunityHotRankingServiceTest {
    private CommunityHotRankingMapper mapper;
    private CommunityHotRankingProperties properties;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private ValueOperations<String, String> values;
    private CommunityPublicationService publications;
    private List<Runnable> asyncTasks;
    private CommunityHotRankingService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityHotRankingMapper.class);
        properties = new CommunityHotRankingProperties();
        properties.validate();
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        values = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        publications = mock(CommunityPublicationService.class);
        asyncTasks = new ArrayList<>();
        Executor executor = asyncTasks::add;
        service =
                new CommunityHotRankingService(
                        mapper, properties, redis, publications, executor);
    }

    @Test
    void schemeCRanksDiscussionFreshnessAboveStaleHighLikes() {
        double freshDiscussion = CommunityHotRankingService.schemeCScore(2, 1, 5, 20, 3);
        double staleViral = CommunityHotRankingService.schemeCScore(80, 40, 0, 2000, 24 * 40);
        assertTrue(freshDiscussion > staleViral);
    }

    @Test
    void emptyMarkerIsCacheHitAndNeverHitsMysql() {
        when(zset.reverseRangeWithScores(eq(properties.getKey()), eq(0L), anyLong()))
                .thenReturn(Set.of(tuple(CommunityHotRankingService.EMPTY_MEMBER, 0)));
        long now = System.currentTimeMillis();
        when(values.get(properties.metaKey())).thenReturn(now + "|" + (now + 60_000));
        when(publications.listForHotRanking(anyLong(), anyList(), anyInt())).thenReturn(List.of());

        HotRankingDtos.HotArticlesResponse response = service.hotArticles(1L, 10);

        assertEquals(0, response.total());
        verify(mapper, never()).selectTopScores(anyInt());
        assertTrue(asyncTasks.isEmpty());
    }

    @Test
    void hydrateUsesSingleBatchListForHotRanking() {
        LinkedHashSet<ZSetOperations.TypedTuple<String>> ranked =
                new LinkedHashSet<>(List.of(tuple("11", 9), tuple("22", 8), tuple("33", 7)));
        when(zset.reverseRangeWithScores(eq(properties.getKey()), eq(0L), anyLong()))
                .thenReturn(ranked);
        long now = System.currentTimeMillis();
        when(values.get(properties.metaKey())).thenReturn(now + "|" + (now + 60_000));
        when(publications.listForHotRanking(eq(7L), anyList(), eq(10)))
                .thenReturn(List.of(article("11"), article("33")));

        HotRankingDtos.HotArticlesResponse response = service.hotArticles(7L, 10);

        assertEquals(2, response.total());
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(publications, times(1)).listForHotRanking(eq(7L), ids.capture(), eq(10));
        assertEquals(List.of(11L, 22L, 33L), ids.getValue());
        verify(publications, never()).get(any(), anyLong(), anyBoolean());
        verify(mapper, never()).selectTopScores(anyInt());
    }

    @Test
    void logicalExpiredServesStaleAndTriggersAsyncRebuildWithoutSyncSql() {
        LinkedHashSet<ZSetOperations.TypedTuple<String>> ranked =
                new LinkedHashSet<>(List.of(tuple("11", 9), tuple("22", 8)));
        when(zset.reverseRangeWithScores(eq(properties.getKey()), eq(0L), anyLong()))
                .thenReturn(ranked);
        long generated = System.currentTimeMillis() - 120_000;
        when(values.get(properties.metaKey())).thenReturn(generated + "|0");
        when(publications.listForHotRanking(eq(7L), anyList(), eq(10)))
                .thenReturn(List.of(article("11"), article("22")));

        HotRankingDtos.HotArticlesResponse response = service.hotArticles(7L, 10);

        assertEquals(2, response.total());
        verify(mapper, never()).selectTopScores(anyInt());
        assertEquals(1, asyncTasks.size());

        when(values.setIfAbsent(
                        eq(properties.lockKey()), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(mapper.selectTopScores(properties.getRebuildLimit()))
                .thenReturn(List.of(new HotArticleScore(11, 9)));
        asyncTasks.getFirst().run();
        verify(mapper, times(1)).selectTopScores(properties.getRebuildLimit());
        verify(redis)
                .execute(any(RedisScript.class), eq(List.of(properties.lockKey())), anyString());
    }

    @Test
    void redisMissDoesNotBlockOrFanOutSqlAndUsesSnapshotWhenPresent() {
        when(mapper.selectTopScores(anyInt()))
                .thenReturn(List.of(new HotArticleScore(55, 3.5)));
        when(publications.listForHotRanking(eq(2L), anyList(), eq(10)))
                .thenReturn(List.of(article("55")));
        // 第一次 Redis 挂掉 → 单飞建快照；第二次 MISS → 吃快照 + 异步重建
        when(zset.reverseRangeWithScores(eq(properties.getKey()), eq(0L), anyLong()))
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn(Set.of());

        HotRankingDtos.HotArticlesResponse first = service.hotArticles(2L, 10);
        assertEquals(1, first.total());
        verify(mapper, times(1)).selectTopScores(anyInt());

        asyncTasks.clear();
        reset(mapper);
        HotRankingDtos.HotArticlesResponse second = service.hotArticles(2L, 10);
        assertEquals(1, second.total());
        verify(mapper, never()).selectTopScores(anyInt());
        assertEquals(1, asyncTasks.size());
    }

    @Test
    void redisFailureSingleFlightHitsMysqlOnce() throws Exception {
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));
        AtomicInteger sqlCalls = new AtomicInteger();
        when(mapper.selectTopScores(anyInt()))
                .thenAnswer(
                        inv -> {
                            sqlCalls.incrementAndGet();
                            Thread.sleep(120);
                            return List.of(
                                    new HotArticleScore(101, 1.2), new HotArticleScore(102, 1.1));
                        });
        when(publications.listForHotRanking(eq(3L), anyList(), eq(10)))
                .thenReturn(List.of(article("101"), article("102")));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            service.hotArticles(3L, 10);
                        } catch (Exception ignored) {
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, sqlCalls.get());
    }

    @Test
    void releaseLockUsesLuaWithToken() {
        when(values.setIfAbsent(
                        eq(properties.lockKey()), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(mapper.selectTopScores(properties.getRebuildLimit())).thenReturn(List.of());
        when(zset.add(anyString(), anySet())).thenReturn(1L);

        assertTrue(service.rebuildCache());

        ArgumentCaptor<Object> token = ArgumentCaptor.forClass(Object.class);
        verify(redis)
                .execute(
                        any(RedisScript.class),
                        eq(List.of(properties.lockKey())),
                        token.capture());
        assertNotNull(token.getValue());
        assertFalse(String.valueOf(token.getValue()).isBlank());
        verify(redis, never()).delete(properties.lockKey());
    }

    private static ZSetOperations.TypedTuple<String> tuple(String member, double score) {
        return new DefaultTypedTuple<>(member, score);
    }

    private static PublicationDtos.Article article(String id) {
        return new PublicationDtos.Article(
                id,
                "t",
                "s",
                "",
                null,
                List.of(),
                null,
                "PUBLISHED",
                1,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                0,
                0,
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                List.of(),
                List.of(),
                0,
                "PUBLIC",
                1,
                List.of());
    }
}
