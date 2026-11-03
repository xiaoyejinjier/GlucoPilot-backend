package com.threadfit.glucopilot.food.entity;

/** 食品目录的数据库身份与当前已提交版本，只用于缓存键，不返回前端。 */
public record FoodCatalogCacheVersion(String cacheNamespace, long revision) {}
