package com.threadfit.glucopilot.meal.suitability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;

/**
 * 适食规则 Mapper。
 * <p>由 adapter 的 {@code @MapperScan(basePackages = "com.threadfit.glucopilot", markerInterface = BaseMapper.class)}
 * 统一注册，无需 {@code @Mapper} 注解（与 MealRecordMapper 一致）。
 */
public interface FoodSuitabilityRuleMapper extends BaseMapper<FoodSuitabilityRule> {
}
