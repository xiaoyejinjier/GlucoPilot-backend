package com.threadfit.glucopilot.meal.support;

import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;

import java.util.Map;

/**
 * 餐食分析文案：档位标签、单项建议、整餐摘要统一在此维护。
 * <p>只允许「升糖负担较低/中等/较高/信息不足」类表述，禁止「适合/不建议食用」等绝对或医疗结论。
 */
public final class MealAnalysisTexts {

    private MealAnalysisTexts() {
    }

    private static final Map<String, String> LABELS = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "升糖负担较低",
            SuitabilityRuleEngine.TIER_MODERATE, "升糖负担中等",
            SuitabilityRuleEngine.TIER_HIGH, "升糖负担较高",
            SuitabilityRuleEngine.TIER_UNKNOWN, "信息不足");

    private static final Map<String, String> ADVICE = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "该类食物升糖负担较低，注意整体份量与搭配即可。",
            SuitabilityRuleEngine.TIER_MODERATE, "该类食物升糖负担中等，建议控制份量并搭配蔬菜或蛋白质。",
            SuitabilityRuleEngine.TIER_HIGH, "该类食物升糖负担较高，建议减少份量或替换为低升糖主食。",
            SuitabilityRuleEngine.TIER_UNKNOWN, "信息不足，建议补充份量或选择库内食物。");

    private static final Map<String, String> SUMMARY = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "本餐整体升糖负担较低，保持均衡搭配即可。",
            SuitabilityRuleEngine.TIER_MODERATE, "本餐整体升糖负担中等，注意主食份量与进食顺序。",
            SuitabilityRuleEngine.TIER_HIGH, "本餐整体升糖负担较高，可减少高升糖主食并增加蔬菜比例。",
            SuitabilityRuleEngine.TIER_UNKNOWN, "本餐信息不足，建议补充份量或选择库内食物。");

    public static String labelOf(String tier) {
        return LABELS.getOrDefault(tier, LABELS.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }

    public static String adviceOf(String tier) {
        return ADVICE.getOrDefault(tier, ADVICE.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }

    public static String summaryOf(String tier) {
        return SUMMARY.getOrDefault(tier, SUMMARY.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }
}
