package com.threadfit.glucopilot.food.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.food.config.FoodCatalogCacheProperties;
import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.mapper.FoodCatalogCacheVersionMapper;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实MyBatis、生产迁移和Spring事务代理，验证跨端缓存更新及回滚。 */
class FoodCatalogCacheIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private AnnotationConfigApplicationContext context;
    private FoodService foods;
    private AdminFoodService admin;
    private FoodCatalogCacheVersionMapper versions;
    private final Map<String, String> cacheValues = new ConcurrentHashMap<>();

    @Configuration
    @EnableTransactionManagement
    static class Transactions {}

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:food_cache_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        String audit =
                "id BIGINT AUTO_INCREMENT PRIMARY KEY,created_by BIGINT,gmt_create"
                    + " DATETIME,updated_by BIGINT,gmt_modified DATETIME,deleted_by"
                    + " BIGINT,deleted_at DATETIME,is_deleted INT DEFAULT 0";
        jdbc.execute(
                "CREATE TABLE food_category ("
                        + audit
                        + ",category_name VARCHAR(64),"
                        + "image_url VARCHAR(255),sort_no INT DEFAULT 0,is_enabled INT DEFAULT 1)");
        jdbc.execute(
                "CREATE TABLE food_nutrition ("
                        + audit
                        + ",food_name VARCHAR(64),category_id BIGINT,category_code"
                        + " INT,carbs_per_100g DECIMAL(10,2),calories_per_100g"
                        + " DECIMAL(10,2),gi_value DECIMAL(10,2),image_url"
                        + " VARCHAR(255),shelf_status INT,nutrition_source VARCHAR(32),source_model"
                        + " VARCHAR(64),source_media_id BIGINT)");
        var migration =
                new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V52__food_catalog_cache_version.sql"));
        migration.setSqlScriptEncoding("UTF-8");
        migration.execute(dataSource);
        jdbc.update(
                "INSERT INTO food_category(id,category_name,image_url) VALUES"
                        + "(1,'水果','/api/v1/foods/images/10')");
        jdbc.update(
                "INSERT INTO food_nutrition(id,food_name,category_id,category_code,carbs_per_100g,"
                        + "calories_per_100g,image_url,shelf_status,nutrition_source) VALUES"
                        + "(7,'葡萄',1,1,25.90,116.0,'/api/v1/foods/images/72',1,'MANUAL')");

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        config.addMapper(FoodCategoryMapper.class);
        config.addMapper(FoodNutritionMapper.class);
        config.addMapper(FoodCatalogCacheVersionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        var sessions = new SqlSessionTemplate(factory.getObject());
        versions = sessions.getMapper(FoodCatalogCacheVersionMapper.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenAnswer(invocation -> cacheValues.get(invocation.getArgument(0)));
        doAnswer(
                        invocation -> {
                            cacheValues.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(operations)
                .set(anyString(), anyString(), any(Duration.class));

        context = new AnnotationConfigApplicationContext();
        context.registerBean(
                "transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(
                FoodCategoryMapper.class, () -> sessions.getMapper(FoodCategoryMapper.class));
        context.registerBean(
                FoodNutritionMapper.class, () -> sessions.getMapper(FoodNutritionMapper.class));
        context.registerBean(FoodCatalogCacheVersionMapper.class, () -> versions);
        context.registerBean(StringRedisTemplate.class, () -> redis);
        context.registerBean(JsonUtils.class, () -> new JsonUtils(new ObjectMapper()));
        context.registerBean(FoodCatalogCacheProperties.class, FoodCatalogCacheProperties::new);
        context.register(
                Transactions.class,
                FoodCatalogCache.class,
                FoodService.class,
                AdminFoodService.class);
        context.refresh();
        foods = context.getBean(FoodService.class);
        admin = context.getBean(AdminFoodService.class);
        RequestContext.setOpsId(9L);
    }

    @AfterEach
    void cleanup() {
        RequestContext.clear();
        if (context != null) {
            context.close();
        }
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void adminPhotoChangesAndShelfDeletionImmediatelyUseNewCommittedVersions() {
        assertEquals("/api/v1/foods/images/72", foods.getFoodDetail(7L).getImageUrl());
        var request = foodRequest("/api/v1/foods/images/99", true);
        admin.saveFood(request);
        assertEquals(2, versions.selectVersion().revision());
        assertEquals("/api/v1/foods/images/99", foods.getFoodDetail(7L).getImageUrl());

        request.setEnabled(false);
        admin.saveFood(request);
        assertThrows(BizException.class, () -> foods.getFoodDetail(7L));
        request.setEnabled(true);
        admin.saveFood(request);
        assertEquals("/api/v1/foods/images/99", foods.getFoodDetail(7L).getImageUrl());
        admin.removeFood(7L);
        assertThrows(BizException.class, () -> foods.getFoodDetail(7L));
        assertEquals(5, versions.selectVersion().revision());
    }

    @Test
    void categoryEditsInvalidateBothCategoryImagesAndNamesInFoodDetails() {
        assertEquals("水果", foods.listFoodCategories().getFirst().getCategoryName());
        assertEquals("水果", foods.getFoodDetail(7L).getCategoryName());
        var request = new AdminFoodDtos.FoodCategorySaveRequest();
        request.setId(1L);
        request.setName("新水果分类");
        request.setImage("/api/v1/foods/images/11");
        admin.saveCategory(request);
        assertEquals("新水果分类", foods.listFoodCategories().getFirst().getCategoryName());
        assertEquals(
                "/api/v1/foods/images/11", foods.listFoodCategories().getFirst().getImageUrl());
        assertEquals("新水果分类", foods.getFoodDetail(7L).getCategoryName());

        assertThrows(BizException.class, () -> admin.removeCategory(1L));
        assertEquals(2, versions.selectVersion().revision());
        admin.removeFood(7L);
        admin.removeCategory(1L);
        assertTrue(foods.listFoodCategories().isEmpty());
    }

    @Test
    void rolledBackAdminEditsAndTransactionReadsCannotPoisonSharedCache() {
        assertEquals("/api/v1/foods/images/72", foods.getFoodDetail(7L).getImageUrl());
        var originalCache = Map.copyOf(cacheValues);
        transaction.executeWithoutResult(
                status -> {
                    admin.saveFood(foodRequest("/api/v1/foods/images/99", true));
                    assertEquals(2, versions.selectVersion().revision());
                    assertEquals("/api/v1/foods/images/99", foods.getFoodDetail(7L).getImageUrl());
                    status.setRollbackOnly();
                });
        assertEquals(1, versions.selectVersion().revision());
        assertEquals(originalCache, cacheValues);
        assertEquals("/api/v1/foods/images/72", foods.getFoodDetail(7L).getImageUrl());
        assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> context.getBean(FoodCatalogCache.class).advanceCatalogVersion());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FOOD_CACHE_REDIS_TEST_PORT", matches = "\\d+")
    void actualRedisStoresJsonWithTtlAndUsesFreshVersionAfterUpdates() {
        String host = System.getenv().getOrDefault("FOOD_CACHE_REDIS_TEST_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv("FOOD_CACHE_REDIS_TEST_PORT"));
        var connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        var settings = new FoodCatalogCacheProperties();
        settings.setKeyPrefix("glucopilot:food:test:" + UUID.randomUUID());
        settings.setTtl(Duration.ofSeconds(30));
        var cache =
                new FoodCatalogCache(versions, redis, new JsonUtils(new ObjectMapper()), settings);
        String prefix =
                settings.getKeyPrefix() + ":" + versions.selectVersion().cacheNamespace() + ":";
        var keys = List.of(prefix + "1:detail:7", prefix + "2:detail:7", prefix + "2:categories");
        var count = new AtomicInteger();
        try {
            try (var ping = connection.getConnection()) {
                assertEquals("PONG", ping.ping());
            }
            cache.foodDetail(
                    7L,
                    () -> {
                        count.incrementAndGet();
                        return FoodCatalogCacheTest.food("7", "原值", "photo-old");
                    });
            assertEquals("原值", cache.foodDetail(7L, () -> fail("实际Redis应命中")).getFoodName());
            assertEquals(1, count.get());
            assertNotNull(redis.opsForValue().get(keys.getFirst()));
            Long ttl = redis.getExpire(keys.getFirst(), TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 30);

            transaction.executeWithoutResult(status -> cache.advanceCatalogVersion());
            assertEquals(
                    "photo-new",
                    cache.foodDetail(7L, () -> FoodCatalogCacheTest.food("7", "新值", "photo-new"))
                            .getImageUrl());
            assertTrue(cache.categories(List::of).isEmpty());
            assertTrue(cache.categories(() -> fail("实际Redis应缓存空分类")).isEmpty());
            // 只操作测试随机命名空间内的明确键；不扫描或清空用户Redis数据。
            redis.delete(keys.get(1));
            assertEquals(
                    "重新加载",
                    cache.foodDetail(
                                    7L,
                                    () -> FoodCatalogCacheTest.food("7", "重新加载", "photo-reload"))
                            .getFoodName());
        } finally {
            try {
                redis.delete(keys);
            } finally {
                connection.destroy();
            }
        }
    }

    private AdminFoodDtos.FoodSaveRequest foodRequest(String image, boolean enabled) {
        var request = new AdminFoodDtos.FoodSaveRequest();
        request.setId(7L);
        request.setName("葡萄");
        request.setCategoryId(1L);
        request.setCarbs(new BigDecimal("25.90"));
        request.setCal(new BigDecimal("116.0"));
        request.setImage(image);
        request.setEnabled(enabled);
        return request;
    }
}
