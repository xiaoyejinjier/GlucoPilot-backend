package com.threadfit.glucopilot.food.mapper;

import com.threadfit.glucopilot.food.entity.FoodCatalogCacheVersion;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 缓存版本由MySQL维护，与食品写操作共用事务，不依赖Redis可用性。 */
@Mapper
public interface FoodCatalogCacheVersionMapper {
    @Select("SELECT cache_namespace,revision FROM food_catalog_cache_version WHERE id=1")
    @ConstructorArgs({
        @Arg(column = "cache_namespace", javaType = String.class),
        @Arg(column = "revision", javaType = long.class)
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    FoodCatalogCacheVersion selectVersion();

    @Update(
            "UPDATE food_catalog_cache_version SET revision=revision+1,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE id=1")
    int advanceVersion();
}
