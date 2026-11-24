package com.threadfit.glucopilot.meal.suitability.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule;
import com.threadfit.glucopilot.meal.suitability.entity.MealSuitabilityConfig;
import com.threadfit.glucopilot.meal.suitability.mapper.MealPortionAdjustRuleMapper;
import com.threadfit.glucopilot.meal.suitability.mapper.MealSuitabilityConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 适食判定配置装配：读取 meal_suitability_config 与 meal_portion_adjust_rule，
 * 供 preview 与 save 两条路径共用（同一配置源，保证文案与聚合口径一致）。
 * <p>只读，不写库；阈值与文案全部来自表，本类不写死任何业务数值。
 */
@Component
@RequiredArgsConstructor
public class SuitabilityConfigService {

    public static final String KEY_SHARE_THRESHOLD = "meal_carb_share_threshold";
    public static final String KEY_UNKNOWN_SKIP = "meal_unknown_skip_enabled";
    public static final String KEY_ADVICE_PREFIX = "advice_";
    public static final String KEY_FOCUS_TEXT_INTRINSIC = "focus_text_intrinsic";
    public static final String KEY_FOCUS_TEXT_PORTION = "focus_text_portion";

    private final MealSuitabilityConfigMapper configMapper;
    private final MealPortionAdjustRuleMapper adjustRuleMapper;

    /** 配置键值对（文案池用 | 分隔候选）。 */
    public Map<String, String> configMap() {
        Map<String, String> map = new HashMap<>();
        for (MealSuitabilityConfig row : configMapper.selectList(Wrappers.emptyWrapper())) {
            if (row.getConfigKey() != null && row.getConfigValue() != null) {
                map.put(row.getConfigKey(), row.getConfigValue());
            }
        }
        return map;
    }

    /** 启用的份量修正规则（含排序由引擎负责）。 */
    public List<MealPortionAdjustRule> adjustRules() {
        return adjustRuleMapper.selectList(
                Wrappers.<MealPortionAdjustRule>lambdaQuery().eq(MealPortionAdjustRule::getIsEnabled, 1));
    }

    /** 整餐聚合参数：配置缺失时取算法中性默认值（门槛 0=不限、跳过 UNKNOWN）。 */
    public SuitabilityRuleEngine.AggregationParams aggregationParams(Map<String, String> config) {
        BigDecimal threshold = parseDecimal(config.get(KEY_SHARE_THRESHOLD));
        boolean skipUnknown = !"0".equals(config.get(KEY_UNKNOWN_SKIP));
        return new SuitabilityRuleEngine.AggregationParams(
                threshold == null || threshold.signum() < 0 ? BigDecimal.ZERO : threshold, skipUnknown);
    }

    /** 重点关注文案：{foodName} 占位符替换；配置缺失时返回 null 由前端兜底。 */
    public String focusText(Map<String, String> config, String reason, String foodName) {
        String template = MealSuitabilityDtos.FOCUS_PORTION.equals(reason)
                ? config.get(KEY_FOCUS_TEXT_PORTION) : config.get(KEY_FOCUS_TEXT_INTRINSIC);
        return AdviceResolver.replacePlaceholders(template, foodName, null, null);
    }

    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
