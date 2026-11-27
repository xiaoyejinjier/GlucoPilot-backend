package com.threadfit.glucopilot.meal.suitability;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适食引擎单测（2026-11-27 去份量口径）：
 * <ul>
 *   <li>ITEM：只看 per100gGL 分档，边界由 priority 消解（MODERATE 10 &lt; LOW 20 &lt; HIGH 30）；</li>
 *   <li>MEAL：取最差一档（worst-of），UNKNOWN 不参与；</li>
 *   <li>克重（carbsG）不再影响档位，只影响营养展示与整餐合计。</li>
 * </ul>
 * 规则值与 V94 种子保持同值。
 */
class SuitabilityRuleEngineTest {

    private final SuitabilityRuleEngine engine = new SuitabilityRuleEngine();

    // ===== ITEM 阶段：per100gGL 分档 =====

    @Test
    void lowGlHitsLowRule() {
        // 西瓜：GI 72 × 每100g碳水 6g / 100 = 4.32 → LOW（高 GI 低密度食物不再被误判）
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(
                glRules(), List.of(glItem("西瓜", "4.32", "40")));

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_LOW, result.getTier());
        assertEquals("GL_ITEM_LOW", result.getRuleCode());
    }

    @Test
    void boundaryTenAndTwentyFallIntoModerate() {
        // 口径：10 ≤ x ≤ 20 → MODERATE（MODERATE priority 最高，消解边界重叠）
        assertEquals("GL_ITEM_MODERATE", engine.evaluate(glRules(),
                List.of(glItem("边界10", "10.0", "30")))
                .getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_MODERATE", engine.evaluate(glRules(),
                List.of(glItem("边界20", "20.0", "30")))
                .getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_LOW", engine.evaluate(glRules(),
                List.of(glItem("边界9.9", "9.9", "30")))
                .getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_HIGH", engine.evaluate(glRules(),
                List.of(glItem("边界20.1", "20.1", "30")))
                .getItems().get(0).getRuleCode());
    }

    @Test
    void ruleOrderDoesNotDependOnInputOrder() {
        List<FoodSuitabilityRule> reversed = new ArrayList<>(glRules());
        Collections.reverse(reversed);

        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(
                reversed, List.of(glItem("米饭", "21.6", "45")));

        assertEquals("GL_ITEM_HIGH", evaluation.getItems().get(0).getRuleCode());
    }

    @Test
    void missingGlDegradesToUnknown() {
        // UNRESOLVED / 缺 GI / 缺每100g碳水：per100gGL 为空 → UNKNOWN，不用 0 冒充
        MealSuitabilityDtos.ItemInput noGl = new MealSuitabilityDtos.ItemInput();
        noGl.setFoodName("自定义菜");
        noGl.setCarbsG(new BigDecimal("50"));

        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(noGl));

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, result.getTier());
        assertNull(result.getRuleCode());
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    // ===== 去份量：克重不影响档位（2026-11-27 起） =====

    @Test
    void weightVariantsNeverChangeTier() {
        // 同一食物重量 ±50%（碳水 15/22.5/34g）：档位与 baseTier 都不动，只看 per100gGL
        for (String carbs : new String[]{"15", "22.5", "34"}) {
            MealSuitabilityDtos.ItemResult result = engine.evaluate(glRules(),
                            List.of(glItem("燕麦", "11.0", carbs)))
                    .getItems().get(0);
            assertEquals(SuitabilityRuleEngine.TIER_MODERATE, result.getTier());
            assertEquals(result.getTier(), result.getBaseTier());
        }
    }

    @Test
    void baseTierAlwaysEqualsTierAfterPortionRetired() {
        // baseTier 是历史字段：份量修正下线后恒等于 tier（前端仍读它，不能乱动语义）
        MealSuitabilityDtos.ItemResult result = engine.evaluate(glRules(),
                        List.of(glItem("薯条小碟", "22.5", "8")))
                .getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, result.getTier());
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, result.getBaseTier());
    }

    // ===== MEAL 阶段：取最差一档（worst-of） =====

    @Test
    void mealTierTakesTheWorstItem() {
        // 杂粮饭 MODERATE + 小碟薯条 HIGH：以前按碳水占比定档 → MODERATE；现在取最差 → HIGH
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("杂粮饭", "12.0", "40"),
                glItem("薯条", "22.5", "8")));

        assertEquals(SuitabilityRuleEngine.TIER_HIGH, evaluation.getOverallTier());
        assertEquals("GL_ITEM_HIGH", evaluation.getWorstRuleCode());
    }

    @Test
    void mealTierLiftsToModerateWhenNoHighItem() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("西瓜", "4.32", "40"),
                glItem("杂粮饭", "12.0", "40")));

        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, evaluation.getOverallTier());
        assertEquals("GL_ITEM_MODERATE", evaluation.getWorstRuleCode());
    }

    @Test
    void unknownItemsDoNotAffectMealTier() {
        // UNKNOWN 既不算「最差」也不把整餐拉黑：有两个已知项时按已知项取最差
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("手输主食", null, "60"),
                glItem("杂粮饭", "12.0", "30"),
                glItem("西瓜", "4.32", "40")));

        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, evaluation.getOverallTier());
        assertEquals("GL_ITEM_MODERATE", evaluation.getWorstRuleCode());
    }

    @Test
    void allUnknownMealStaysUnknown() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("手输菜A", null, "30"),
                glItem("手输菜B", null, "20")));

        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
        assertTrue(evaluation.getFocusItems().isEmpty());
    }

    @Test
    void carbsNoLongerDecideTheMeal() {
        // 只有少量高 per100gGL 的食物（碳水 8g）也能把整餐拉到 HIGH —— 与份量无关
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("无碳水蔬菜", "3.0", null),
                glItem("米饭", "21.6", "45"),
                glItem("薯条一小口", "22.5", "8")));

        assertEquals(SuitabilityRuleEngine.TIER_HIGH, evaluation.getOverallTier());
        assertEquals("GL_ITEM_HIGH", evaluation.getWorstRuleCode());
    }

    // ===== 重点关注 =====

    @Test
    void focusItemsAreIndependentOfMealTier() {
        // 重点关注 = 本身 HIGH 的单项；即整餐已是 HIGH，也让用户知道是哪几样
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("米饭", "21.6", "45"),
                glItem("西瓜", "4.32", "40")));

        assertEquals(1, evaluation.getFocusItems().size());
        assertEquals("米饭", evaluation.getFocusItems().get(0).getFoodName());
        assertEquals(MealSuitabilityDtos.FOCUS_INTRINSIC, evaluation.getFocusItems().get(0).getReason());
        assertTrue(evaluation.getItems().get(0).getFocused());
    }

    @Test
    void noFocusWhenNoHighItem() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("西瓜", "4.32", "40")));

        assertFalse(Boolean.TRUE.equals(evaluation.getItems().get(0).getFocused()));
        assertTrue(evaluation.getFocusItems().isEmpty());
    }

    // ===== 其它口径 =====

    @Test
    void legacyCarbRuleMatchesWithoutShortCircuit() {
        // 带 carbsMin/carbsMax 的旧行按 metric=CARB 正常匹配（V94 起显式短路已删除）
        List<FoodSuitabilityRule> rules = new ArrayList<>(glRules());
        FoodSuitabilityRule carbRule = new FoodSuitabilityRule();
        carbRule.setRuleCode("CARB_DENSE_MODERATE");
        carbRule.setMetric("CARB");
        carbRule.setScope("ITEM");
        carbRule.setCarbsMin(new BigDecimal("20"));
        carbRule.setCarbsMax(new BigDecimal("40"));
        carbRule.setTier("MODERATE");
        carbRule.setPriority(1); // 最高优先级
        carbRule.setIsEnabled(1);
        rules.add(carbRule);

        MealSuitabilityDtos.ItemInput dense = glItem("高碳水主食", "25.0", "50");
        dense.setCarbsPer100g(new BigDecimal("30"));

        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(rules, List.of(dense));
        assertEquals("CARB_DENSE_MODERATE", evaluation.getItems().get(0).getRuleCode());

        // carbsPer100g 为 null 时 CARB 规则不命中（不用 0 冒充）→ 落到 GL 规则
        MealSuitabilityDtos.Evaluation withoutCarbDensity = engine.evaluate(
                rules, List.of(glItem("无碳水密度", "25.0", "50")));
        assertEquals("GL_ITEM_HIGH", withoutCarbDensity.getItems().get(0).getRuleCode());
    }

    @Test
    void sameInputProducesSameResult() {
        List<MealSuitabilityDtos.ItemInput> items = List.of(
                glItem("米饭", "21.6", "45"),
                glItem("西瓜", "4.32", "40"),
                glItem("手输菜", null, "20"));

        MealSuitabilityDtos.Evaluation first = engine.evaluate(glRules(), items);
        MealSuitabilityDtos.Evaluation second = engine.evaluate(glRules(), items);

        assertEquals(first.getOverallTier(), second.getOverallTier());
        assertEquals(first.getWorstRuleCode(), second.getWorstRuleCode());
        for (int i = 0; i < first.getItems().size(); i++) {
            assertEquals(first.getItems().get(i).getTier(), second.getItems().get(i).getTier());
            assertEquals(first.getItems().get(i).getRuleCode(), second.getItems().get(i).getRuleCode());
        }
    }

    @Test
    void emptyItemsStaysUnknown() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of());

        assertTrue(evaluation.getItems().isEmpty());
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    // ===== 测试数据（与 V94 种子同值） =====

    /** V94 新版本规则：三档边界重叠由 priority 消解（MODERATE=10 &lt; LOW=20 &lt; HIGH=30）。 */
    private static List<FoodSuitabilityRule> glRules() {
        return List.of(
                glRule("GL_ITEM_MODERATE", "10.0", "20.0", "MODERATE", 10),
                glRule("GL_ITEM_LOW", null, "10.0", "LOW", 20),
                glRule("GL_ITEM_HIGH", "20.0", null, "HIGH", 30));
    }

    private static FoodSuitabilityRule glRule(String code, String glMin, String glMax, String tier, int priority) {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode(code);
        rule.setMetric("GL");
        rule.setScope("ITEM");
        rule.setGlMin(glMin == null ? null : new BigDecimal(glMin));
        rule.setGlMax(glMax == null ? null : new BigDecimal(glMax));
        rule.setTier(tier);
        rule.setPriority(priority);
        rule.setRuleVersion("2026.11.1");
        rule.setIsEnabled(1);
        return rule;
    }

    private static MealSuitabilityDtos.ItemInput glItem(String foodName, String per100gGl, String carbsG) {
        MealSuitabilityDtos.ItemInput item = new MealSuitabilityDtos.ItemInput();
        item.setFoodName(foodName);
        item.setPer100gGl(per100gGl == null ? null : new BigDecimal(per100gGl));
        item.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        return item;
    }
}
