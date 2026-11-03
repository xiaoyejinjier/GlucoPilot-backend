package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.food.config.FoodCatalogCacheProperties;
import com.threadfit.glucopilot.food.dto.FoodCacheSnapshots;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.mapper.FoodCatalogCacheVersionMapper;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 食品分类与详情的Redis旁路缓存；MySQL版本保证更新、回滚和迟到回填的隔离。 */
@Service
@RequiredArgsConstructor
public class FoodCatalogCache {
    private static final Logger log = LoggerFactory.getLogger(FoodCatalogCache.class);
    private static final long REDIS_RETRY_DELAY_MILLIS = 5_000;

    private final FoodCatalogCacheVersionMapper versions;
    private final StringRedisTemplate redis;
    private final JsonUtils json;
    private final FoodCatalogCacheProperties properties;
    private final AtomicLong redisRetryAt = new AtomicLong();

    /** 【入口一】缓存启用分类列表，空列表也可缓存，图片只保存地址。 */
    public List<FoodDtos.FoodCategoryItem> categories(
            Supplier<List<FoodDtos.FoodCategoryItem>> databaseQuery) {
        return getOrLoad(
                        "categories",
                        FoodCacheSnapshots.Categories.class,
                        () -> FoodCacheSnapshots.Categories.from(databaseQuery.get()))
                .toItems();
    }

    /** 【入口二】缓存上架食品详情；查库抛出的404继续向外返回，不缓存错误。 */
    public FoodDtos.FoodItem foodDetail(Long foodId, Supplier<FoodDtos.FoodItem> databaseQuery) {
        if (foodId == null) {
            return databaseQuery.get();
        }
        return getOrLoad(
                        "detail:" + foodId,
                        FoodCacheSnapshots.Food.class,
                        () -> FoodCacheSnapshots.Food.from(databaseQuery.get()))
                .toItem();
    }

    /** 【写后失效】必须加入食品写事务。只有事务提交后，其他请求才看到新版本； Redis宕机也不影响版本递增，旧缓存和迟到回填均留在旧版本键下，随后按TTL回收。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceCatalogVersion() {
        if (versions.advanceVersion() != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "食品缓存版本未初始化，请检查数据库迁移");
        }
    }

    /** 【读缓存主流程】检查版本 → 读Redis → 未命中查MyBatis → 写JSON与TTL。 */
    private <T> T getOrLoad(String resource, Class<T> type, Supplier<T> databaseQuery) {
        // 事务内可能读到未提交数据或较早的事务快照，必须绕过共享缓存。
        if (!properties.isEnabled()
                || TransactionSynchronizationManager.isActualTransactionActive()
                || System.currentTimeMillis() < redisRetryAt.get()) {
            return databaseQuery.get();
        }

        // 每次保留一条轻量主键查询；不把这个版本再缓存，避免Redis故障后恢复旧版本。
        var version = versions.selectVersion();
        if (version == null) {
            return databaseQuery.get();
        }
        String key =
                properties.getKeyPrefix()
                        + ":"
                        + version.cacheNamespace()
                        + ":"
                        + version.revision()
                        + ":"
                        + resource;
        T cached = read(key, type);
        if (cached != null) {
            return cached;
        }

        // 数据库业务异常不在Redis异常处理范围内，例如下架食品仍返回404。
        T loaded = databaseQuery.get();
        write(key, loaded);
        return loaded;
    }

    private <T> T read(String key, Class<T> type) {
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (RuntimeException ex) {
            redisUnavailable(ex);
            return null;
        }
        if (value == null) {
            return null;
        }
        try {
            return json.fromJson(value, type);
        } catch (RuntimeException ex) {
            // 损坏或旧格式当作未命中，查库成功后覆盖；不把缓存解析失败返回给用户。
            log.warn("食品缓存JSON不可用，将重新查询数据库");
            return null;
        }
    }

    private void write(String key, Object value) {
        if (System.currentTimeMillis() < redisRetryAt.get()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.toJson(value), properties.getTtl());
        } catch (RuntimeException ex) {
            redisUnavailable(ex);
        }
    }

    private void redisUnavailable(RuntimeException ex) {
        long now = System.currentTimeMillis();
        long previous = redisRetryAt.getAndSet(now + REDIS_RETRY_DELAY_MILLIS);
        if (previous <= now) {
            // 不输出连接串、密钥、缓存正文，避免泄露环境配置或形成重复报错刷屏。
            log.warn("食品Redis读缓存暂不可用，5秒内直接查库，异常类型={}", ex.getClass().getSimpleName());
        }
    }
}
