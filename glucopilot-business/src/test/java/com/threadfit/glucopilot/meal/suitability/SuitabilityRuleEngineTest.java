package com.threadfit.glucopilot.meal.suitability;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适食引擎单测：只验证计算通路（分类命中 / GI 覆盖 / 兜底 / 整餐取最差 / 结果稳定）。
 * 规则集与 V88 占位种子保持同值，仅去掉 DB 字段。
 */
class SuitabilityRuleEngineTest {

    private final SuitabilityRuleEngine engine = new SuitabilityRuleEngine();

    @Test
    void categoryRuleHit() {
        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(placeholderRules(), List.of(item(1L, "清炒西兰花", 3, null)));

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_LOW, result.getTier());
        assertEquals("CAT3_VEG_LOW", result.getRuleCode());
        assertEquals(SuitabilityRuleEngine.TIER_LOW, evaluation.getOverallTier());
        assertEquals("CAT3_VEG_LOW", evaluation.getWorstRuleCode());
    }

    @Test
    void giRuleOverridesCategoryRule() {
        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(placeholderRules(), List.of(item(2L, "白粥", 1, "75.0")));

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, result.getTier());
        assertEquals("GI_HIGH_70", result.getRuleCode());
    }

    @Test
    void stapleWithoutGiFallsBackToModerate() {
        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(placeholderRules(), List.of(item(3L, "米饭", 1, null)));

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, result.getTier());
        assertEquals("CAT1_STAPLE_MODERATE", result.getRuleCode());
    }

    @Test
    void unmappedCategoryBecomesUnknown() {
        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(placeholderRules(), List.of(item(4L, "牛奶", 5, null)));

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, result.getTier());
        assertNull(result.getRuleCode());
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    @Test
    void wholeMealTakesWorstItem() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(placeholderRules(), List.of(
                item(5L, "清炒青菜", 3, null),
                item(6L, "可乐", 8, null)));

        assertEquals(SuitabilityRuleEngine.TIER_HIGH, evaluation.getOverallTier());
        assertEquals("CAT8_DRINK_HIGH", evaluation.getWorstRuleCode());
        assertEquals(2, evaluation.getItems().size());
    }

    @Test
    void unknownItemsDoNotDragDownKnownOnes() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(placeholderRules(), List.of(
                item(7L, "牛奶", 5, null),
                item(8L, "清炒青菜", 3, null)));

        assertEquals(SuitabilityRuleEngine.TIER_LOW, evaluation.getOverallTier());
        assertEquals("CAT3_VEG_LOW", evaluation.getWorstRuleCode());
    }

    @Test
    void allUnknownMealStaysUnknown() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(placeholderRules(), List.of(
                item(9L, "牛奶", 5, null),
                item(10L, "核桃", 7, null)));

        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    @Test
    void giBoundaryIsInclusive() {
        MealSuitabilityDtos.Evaluation atBoundary =
                engine.evaluate(placeholderRules(), List.of(item(11L, "边界70", 1, "70.0")));
        assertEquals("GI_HIGH_70", atBoundary.getItems().get(0).getRuleCode());

        MealSuitabilityDtos.Evaluation belowBoundary =
                engine.evaluate(placeholderRules(), List.of(item(12L, "边界69.9", 1, "69.9")));
        assertEquals("CAT1_STAPLE_MODERATE", belowBoundary.getItems().get(0).getRuleCode());
    }

    @Test
    void ruleOrderDoesNotDependOnInputOrder() {
        List<FoodSuitabilityRule> reversed = new ArrayList<>(placeholderRules());
        java.util.Collections.reverse(reversed);

        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(reversed, List.of(item(13L, "白粥", 1, "88.0")));

        assertEquals("GI_HIGH_70", evaluation.getItems().get(0).getRuleCode());
    }

    @Test
    void disabledRuleIsIgnored() {
        List<FoodSuitabilityRule> rules = new ArrayList<>(placeholderRules());
        rules.stream()
                .filter(r -> "CAT3_VEG_LOW".equals(r.getRuleCode()))
                .forEach(r -> r.setIsEnabled(0));

        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(rules, List.of(item(14L, "清炒青菜", 3, null)));

        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getItems().get(0).getTier());
        assertNull(evaluation.getItems().get(0).getRuleCode());
    }

    @Test
    void sameInputProducesSameResult() {
        List<MealSuitabilityDtos.ItemInput> items = Arrays.asList(
                item(15L, "白粥", 1, "75.0"),
                item(16L, "可乐", 8, null),
                item(17L, "牛奶", 5, null));

        MealSuitabilityDtos.Evaluation first = engine.evaluate(placeholderRules(), items);
        MealSuitabilityDtos.Evaluation second = engine.evaluate(placeholderRules(), items);

        assertEquals(first.getOverallTier(), second.getOverallTier());
        assertEquals(first.getWorstRuleCode(), second.getWorstRuleCode());
        assertEquals(first.getItems().size(), second.getItems().size());
        for (int i = 0; i < first.getItems().size(); i++) {
            assertEquals(first.getItems().get(i).getTier(), second.getItems().get(i).getTier());
            assertEquals(first.getItems().get(i).getRuleCode(), second.getItems().get(i).getRuleCode());
        }
        assertTrue(first.getItems().stream().anyMatch(r -> "GI_HIGH_70".equals(r.getRuleCode())));
    }

    @Test
    void emptyItemsStaysUnknown() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(placeholderRules(), List.of());

        assertTrue(evaluation.getItems().isEmpty());
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    // ===== 测试数据（与 V88 占位种子同值） =====

    private static List<FoodSuitabilityRule> placeholderRules() {
        return List.of(
                rule("GI_HIGH_70", null, "70.0", "HIGH", 5),
                rule("CAT9_SNACK_HIGH", 9, null, "HIGH", 10),
                rule("CAT8_DRINK_HIGH", 8, null, "HIGH", 10),
                rule("CAT3_VEG_LOW", 3, null, "LOW", 20),
                rule("CAT2_PROTEIN_LOW", 2, null, "LOW", 20),
                rule("CAT6_SOY_LOW", 6, null, "LOW", 20),
                rule("CAT1_STAPLE_MODERATE", 1, null, "MODERATE", 30),
                rule("CAT4_FRUIT_MODERATE", 4, null, "MODERATE", 30));
    }

    private static FoodSuitabilityRule rule(String code, Integer categoryCode, String giMin, String tier, int priority) {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode(code);
        rule.setCategoryCode(categoryCode);
        rule.setGiMin(giMin == null ? null : new BigDecimal(giMin));
        rule.setTier(tier);
        rule.setPriority(priority);
        rule.setRuleVersion("2026.11.0-placeholder");
        rule.setIsEnabled(1);
        return rule;
    }

    private static MealSuitabilityDtos.ItemInput item(Long foodId, String foodName, Integer categoryCode, String giValue) {
        MealSuitabilityDtos.ItemInput item = new MealSuitabilityDtos.ItemInput();
        item.setFoodId(foodId);
        item.setFoodName(foodName);
        item.setCategoryCode(categoryCode);
        item.setGiValue(giValue == null ? null : new BigDecimal(giValue));
        return item;
    }
}
