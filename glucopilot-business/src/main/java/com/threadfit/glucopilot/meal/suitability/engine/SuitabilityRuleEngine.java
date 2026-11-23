package com.threadfit.glucopilot.meal.suitability.engine;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 适食判定引擎（纯函数）：无 Spring 依赖、不读 DB、不写库。
 * <p>输入规则列表与食物列表，输出每项档位 + 整餐档位，便于单测覆盖。
 * <p>匹配顺序：规则按 {@code priority} 升序，首个命中即生效（GI 覆盖 5 先于分类规则 10~30）；
 * 全部不命中为 {@code UNKNOWN}；整餐取最差项，全部 UNKNOWN 则整餐 UNKNOWN。
 * <p>阈值、优先级、档位一律来自规则表，本类只定义档位取值与严重度顺序，不含任何业务数值。
 * <p>1a 只评估 {@code categoryCode} 与 {@code giMin/giMax}；带 {@code carbsMin/carbsMax}
 * 条件的规则因缺少「每 100g 碳水」输入而不参与匹配，待阶段 2 接入营养解析后启用。
 */
public class SuitabilityRuleEngine {

    public static final String TIER_LOW = "LOW";
    public static final String TIER_MODERATE = "MODERATE";
    public static final String TIER_HIGH = "HIGH";
    public static final String TIER_UNKNOWN = "UNKNOWN";

    /** 严重度由低到高；顺序即「取最差」的比较依据。 */
    private static final List<String> TIER_SEVERITY = List.of(TIER_LOW, TIER_MODERATE, TIER_HIGH);

    /**
     * 评估整餐。
     *
     * @param rules 同一 rule_version 下的规则（允许乱序、允许含禁用/无效行，本方法自行过滤排序）
     * @param items 食物项；为空或 null 时整餐为 UNKNOWN
     */
    public MealSuitabilityDtos.Evaluation evaluate(List<FoodSuitabilityRule> rules,
                                                   List<MealSuitabilityDtos.ItemInput> items) {
        List<FoodSuitabilityRule> ordered = orderRules(rules);
        List<MealSuitabilityDtos.ItemResult> results = new ArrayList<>();
        if (items != null) {
            for (MealSuitabilityDtos.ItemInput item : items) {
                if (item == null) {
                    continue;
                }
                FoodSuitabilityRule hit = firstMatch(ordered, item);
                MealSuitabilityDtos.ItemResult result = new MealSuitabilityDtos.ItemResult();
                result.setFoodId(item.getFoodId());
                result.setFoodName(item.getFoodName());
                result.setTier(hit == null ? TIER_UNKNOWN : hit.getTier());
                result.setRuleCode(hit == null ? null : hit.getRuleCode());
                results.add(result);
            }
        }

        MealSuitabilityDtos.Evaluation evaluation = new MealSuitabilityDtos.Evaluation();
        evaluation.setItems(results);
        evaluation.setOverallTier(TIER_UNKNOWN);
        evaluation.setWorstRuleCode(null);

        int worstSeverity = -1;
        for (MealSuitabilityDtos.ItemResult result : results) {
            int severity = severityOf(result.getTier());
            if (severity > worstSeverity) {
                worstSeverity = severity;
                evaluation.setOverallTier(result.getTier());
                evaluation.setWorstRuleCode(result.getRuleCode());
            }
        }
        return evaluation;
    }

    /** 过滤禁用/无效规则，按 priority 升序（null 视为最低优先级）。 */
    private List<FoodSuitabilityRule> orderRules(List<FoodSuitabilityRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<FoodSuitabilityRule> ordered = new ArrayList<>(rules.size());
        for (FoodSuitabilityRule rule : rules) {
            if (rule == null || rule.getIsEnabled() == null || rule.getIsEnabled() != 1) {
                continue;
            }
            if (!TIER_SEVERITY.contains(rule.getTier())) {
                continue;
            }
            ordered.add(rule);
        }
        ordered.sort(Comparator.comparingInt(
                (FoodSuitabilityRule rule) -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority()));
        return ordered;
    }

    private FoodSuitabilityRule firstMatch(List<FoodSuitabilityRule> ordered, MealSuitabilityDtos.ItemInput item) {
        for (FoodSuitabilityRule rule : ordered) {
            if (matches(rule, item)) {
                return rule;
            }
        }
        return null;
    }

    private boolean matches(FoodSuitabilityRule rule, MealSuitabilityDtos.ItemInput item) {
        if (rule.getCategoryCode() != null && !rule.getCategoryCode().equals(item.getCategoryCode())) {
            return false;
        }
        if (rule.getGiMin() != null && (item.getGiValue() == null || !atLeast(item.getGiValue(), rule.getGiMin()))) {
            return false;
        }
        if (rule.getGiMax() != null && (item.getGiValue() == null || !atMost(item.getGiValue(), rule.getGiMax()))) {
            return false;
        }
        // 1a 无「每 100g 碳水」输入：带碳水区间的规则一律视为不命中，避免误判
        return rule.getCarbsMin() == null && rule.getCarbsMax() == null;
    }

    private boolean atLeast(BigDecimal value, BigDecimal bound) {
        return value.compareTo(bound) >= 0;
    }

    private boolean atMost(BigDecimal value, BigDecimal bound) {
        return value.compareTo(bound) <= 0;
    }

    /** 档位严重度：LOW=0 / MODERATE=1 / HIGH=2；UNKNOWN 或未知取值返回 -1（不参与「取最差」）。 */
    private int severityOf(String tier) {
        return TIER_SEVERITY.indexOf(tier);
    }
}
