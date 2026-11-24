package com.threadfit.glucopilot.meal.support;

import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;

import java.util.Map;

/**
 * 餐食分析文案：档位标签与整餐摘要统一在此维护。
 * <p><b>单项建议文案不再维护于此</b>（V94 起）：preview 与 save 共用
 * {@link com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver}，
 * 规则级文案取 food_suitability_rule.advice_text，兜底候选池取 meal_suitability_config。
 * <p>合规口径（话术变更需作为产品决策记录）：只允许「升糖负担 + 行动建议」类表述，
 * 禁止「适合/不适合/可食用/不建议食用」等绝对或医疗结论。
 */
public final class MealAnalysisTexts {

    private MealAnalysisTexts() {
    }

    private static final Map<String, String> LABELS = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "升糖负担较低",
            SuitabilityRuleEngine.TIER_MODERATE, "升糖负担中等",
            SuitabilityRuleEngine.TIER_HIGH, "升糖负担较高",
            SuitabilityRuleEngine.TIER_UNKNOWN, "信息不足");

    private static final Map<String, String> SUMMARY = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "本餐整体升糖负担较低，保持均衡搭配即可。",
            SuitabilityRuleEngine.TIER_MODERATE, "本餐整体升糖负担中等，注意主食份量与进食顺序。",
            SuitabilityRuleEngine.TIER_HIGH, "本餐整体升糖负担较高，可减少高升糖主食并增加蔬菜比例。",
            SuitabilityRuleEngine.TIER_UNKNOWN, "本餐信息不足，建议补充份量或选择库内食物。");

    public static String labelOf(String tier) {
        return LABELS.getOrDefault(tier, LABELS.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }

    public static String summaryOf(String tier) {
        return SUMMARY.getOrDefault(tier, SUMMARY.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }
}
