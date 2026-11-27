package com.threadfit.glucopilot.meal.support;

import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;

import java.util.Map;

/**
 * 餐食分析文案：档位标签与整餐摘要统一在此维护。
 * <p><b>单项建议文案不再维护于此</b>（V94 起）：preview 与 save 共用
 * {@link com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver}，
 * 规则级文案取 food_suitability_rule.advice_text，兜底候选池取 meal_suitability_config。
 * <p>话术口径（变更需作为产品决策记录）：<b>2026-11-27 产品决策</b>——饮食分析不再分析「量」，
 * 判定只回答「这一餐 / 这样食物该不该吃」，因此档位标签直接表达建议
 * （建议食用 / 适量食用 / 不建议食用 / 信息不足），替代 V94 起「只允许升糖负担类表述、
 * 禁止食用类表述」的旧口径。克重与份量仍可录入，但只用于营养合计，不参与判定、也不出现在结论里。
 * <p>免责声明不变：结果仅供参考，不构成医疗建议。
 */
public final class MealAnalysisTexts {

    private MealAnalysisTexts() {
    }

    private static final Map<String, String> LABELS = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "建议食用",
            SuitabilityRuleEngine.TIER_MODERATE, "适量食用",
            SuitabilityRuleEngine.TIER_HIGH, "不建议食用",
            SuitabilityRuleEngine.TIER_UNKNOWN, "信息不足");

    private static final Map<String, String> SUMMARY = Map.of(
            SuitabilityRuleEngine.TIER_LOW, "本餐整体建议食用，保持均衡搭配即可。",
            SuitabilityRuleEngine.TIER_MODERATE, "本餐整体建议适量食用，注意进食顺序与搭配。",
            SuitabilityRuleEngine.TIER_HIGH, "本餐整体不建议食用，可换用升糖负担更低的食物。",
            SuitabilityRuleEngine.TIER_UNKNOWN, "本餐信息不足，请补全食物信息后再看结论。");

    public static String labelOf(String tier) {
        return LABELS.getOrDefault(tier, LABELS.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }

    public static String summaryOf(String tier) {
        return SUMMARY.getOrDefault(tier, SUMMARY.get(SuitabilityRuleEngine.TIER_UNKNOWN));
    }
}
