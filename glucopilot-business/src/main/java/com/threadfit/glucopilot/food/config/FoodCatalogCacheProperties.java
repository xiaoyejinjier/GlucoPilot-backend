package com.threadfit.glucopilot.food.config;

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 复用现有spring.data.redis连接，只配置食品读缓存的开关、有效期和键前缀。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "food.catalog-cache")
public class FoodCatalogCacheProperties {
    private boolean enabled = true;
    private Duration ttl = Duration.ofMinutes(10);
    private String keyPrefix = "glucopilot:food:v1";

    @PostConstruct
    public void validate() {
        if (ttl == null
                || ttl.isZero()
                || ttl.isNegative()
                || ttl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("food.catalog-cache.ttl必须大于0且不超过1天");
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("food.catalog-cache.key-prefix不能为空");
        }
    }
}
