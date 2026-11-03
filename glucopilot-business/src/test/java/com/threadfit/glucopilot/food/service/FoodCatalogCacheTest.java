package com.threadfit.glucopilot.food.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.food.config.FoodCatalogCacheProperties;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.entity.FoodCatalogCacheVersion;
import com.threadfit.glucopilot.food.mapper.FoodCatalogCacheVersionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class FoodCatalogCacheTest {
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong(1);
    private FoodCatalogCacheVersionMapper versions;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> operations;
    private FoodCatalogCacheProperties settings;
    private FoodCatalogCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        versions = mock(FoodCatalogCacheVersionMapper.class);
        redis = mock(StringRedisTemplate.class);
        operations = mock(ValueOperations.class);
        settings = new FoodCatalogCacheProperties();
        when(versions.selectVersion())
                .thenAnswer(
                        invocation -> new FoodCatalogCacheVersion("catalog-test", revision.get()));
        when(versions.advanceVersion())
                .thenAnswer(
                        invocation -> {
                            revision.incrementAndGet();
                            return 1;
                        });
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(
                        invocation -> {
                            values.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(operations)
                .set(anyString(), anyString(), any(Duration.class));
        cache = new FoodCatalogCache(versions, redis, new JsonUtils(new ObjectMapper()), settings);
    }

    @Test
    void categoriesUseJsonCacheIncludingEmptyResultsAndDoNotShareMutableDtos() {
        var count = new AtomicInteger();
        var item =
                FoodDtos.FoodCategoryItem.builder()
                        .id("1")
                        .categoryName("水果")
                        .imageUrl("/api/v1/foods/images/9")
                        .sortNo(2)
                        .build();
        var first =
                cache.categories(
                        () -> {
                            count.incrementAndGet();
                            return List.of(item);
                        });
        first.getFirst().setCategoryName("仅修改调用者对象");
        var second = cache.categories(() -> fail("同版本缓存命中不应重新查询分类"));
        assertEquals("水果", second.getFirst().getCategoryName());
        assertEquals("/api/v1/foods/images/9", second.getFirst().getImageUrl());
        assertEquals(1, count.get());
        verify(operations).set(anyString(), anyString(), eq(Duration.ofMinutes(10)));

        cache.advanceCatalogVersion();
        assertTrue(cache.categories(List::of).isEmpty());
        assertTrue(cache.categories(() -> fail("空分类列表也应命中缓存")).isEmpty());
    }

    @Test
    void detailPreservesDecimalsAndPhotoUrlAndSeparatesDifferentFoodIds() {
        var first = cache.foodDetail(7L, () -> food("7", "葡萄", "/api/v1/foods/images/72"));
        var hit = cache.foodDetail(7L, () -> fail("不应重新查询详情"));
        assertEquals(first, hit);
        assertEquals(new BigDecimal("25.90"), hit.getCarbsPer100g());
        assertNull(hit.getGiValue());
        assertEquals("/api/v1/foods/images/72", hit.getImageUrl());
        assertEquals("苹果", cache.foodDetail(8L, () -> food("8", "苹果", "apple")).getFoodName());
        assertEquals(2, values.size());
    }

    @Test
    void redisReadFailureFallsBackAndBacksOffWithoutChangingDatabaseErrors() {
        when(operations.get(anyString())).thenThrow(new RedisConnectionFailureException("test"));
        var count = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertEquals(
                    "葡萄",
                    cache.foodDetail(
                                    7L,
                                    () -> {
                                        count.incrementAndGet();
                                        return food("7", "葡萄", "photo");
                                    })
                            .getFoodName());
        }
        assertEquals(2, count.get());
        verify(operations, times(1)).get(anyString());
        verify(operations, never()).set(anyString(), anyString(), any(Duration.class));
        var missing =
                assertThrows(
                        BizException.class,
                        () ->
                                cache.foodDetail(
                                        9L,
                                        () -> {
                                            throw new BizException(ErrorCode.NOT_FOUND, "已下架");
                                        }));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
    }

    @Test
    void redisWriteFailureDoesNotFailSuccessfulFoodQuery() {
        doThrow(new RedisConnectionFailureException("test"))
                .when(operations)
                .set(anyString(), anyString(), any(Duration.class));
        assertEquals("葡萄", cache.foodDetail(7L, () -> food("7", "葡萄", "photo")).getFoodName());
        assertEquals("新值", cache.foodDetail(7L, () -> food("7", "新值", "new-photo")).getFoodName());
    }

    @Test
    void malformedCachedJsonIsReplacedAndDoesNotBecomeAClientError() {
        values.put(key(1, "detail:7"), "{broken-json");
        assertEquals("葡萄", cache.foodDetail(7L, () -> food("7", "葡萄", "photo")).getFoodName());
        assertEquals("葡萄", cache.foodDetail(7L, () -> fail("修复缓存后应命中")).getFoodName());
    }

    @Test
    void missingFoodsAreNotCachedAndDisabledCacheUsesDatabase() {
        for (int i = 0; i < 2; i++) {
            assertThrows(
                    BizException.class,
                    () ->
                            cache.foodDetail(
                                    99L,
                                    () -> {
                                        throw new BizException(ErrorCode.NOT_FOUND);
                                    }));
        }
        assertTrue(values.isEmpty());
        clearInvocations(redis, versions);
        settings.setEnabled(false);
        assertEquals("新值", cache.foodDetail(7L, () -> food("7", "新值", "photo")).getFoodName());
        verifyNoInteractions(redis, versions);
        // 即使此实例关闭读缓存，写操作仍需推进版本，让其他实例的缓存失效。
        cache.advanceCatalogVersion();
        assertEquals(2, revision.get());
    }

    @Test
    void readsInsideTransactionsNeverPublishUncommittedValuesToRedis() {
        cache.foodDetail(7L, () -> food("7", "原值", "original"));
        clearInvocations(redis, versions);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(
                    "未提交", cache.foodDetail(7L, () -> food("7", "未提交", "draft")).getFoodName());
            verifyNoInteractions(redis, versions);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertEquals("原值", cache.foodDetail(7L, () -> fail("事务内读取不能覆盖已有缓存")).getFoodName());
    }

    @Test
    void delayedOldFillCannotOverwriteTheNewVersionAfterAnAdminUpdate() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var old =
                    pool.submit(
                            () ->
                                    cache.foodDetail(
                                            7L,
                                            () -> {
                                                started.countDown();
                                                try {
                                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                                } catch (InterruptedException ex) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(ex);
                                                }
                                                return food("7", "旧值", "old-photo");
                                            }));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                cache.advanceCatalogVersion();
                assertEquals(
                        "new-photo",
                        cache.foodDetail(7L, () -> food("7", "新值", "new-photo")).getImageUrl());
            } finally {
                release.countDown();
            }
            assertEquals("旧值", old.get(5, TimeUnit.SECONDS).getFoodName());
            assertEquals(
                    "new-photo", cache.foodDetail(7L, () -> fail("迟到回填不能覆盖新版本")).getImageUrl());
            assertTrue(values.containsKey(key(1, "detail:7")));
            assertTrue(values.containsKey(key(2, "detail:7")));
        }
    }

    @Test
    void separateCatalogsDoNotShareCacheEvenWithTheSameFoodIdAndRevision() {
        cache.foodDetail(7L, () -> food("7", "目录甲", "a"));
        when(versions.selectVersion()).thenReturn(new FoodCatalogCacheVersion("other-catalog", 1));
        assertEquals("目录乙", cache.foodDetail(7L, () -> food("7", "目录乙", "b")).getFoodName());
        assertEquals(2, values.size());
    }

    private String key(long version, String resource) {
        return settings.getKeyPrefix() + ":catalog-test:" + version + ":" + resource;
    }

    static FoodDtos.FoodItem food(String id, String name, String image) {
        return FoodDtos.FoodItem.builder()
                .id(id)
                .foodName(name)
                .categoryId("1")
                .categoryCode(1)
                .categoryName("水果")
                .carbsPer100g(new BigDecimal("25.90"))
                .caloriesPer100g(new BigDecimal("116.0"))
                .imageUrl(image)
                .build();
    }
}
