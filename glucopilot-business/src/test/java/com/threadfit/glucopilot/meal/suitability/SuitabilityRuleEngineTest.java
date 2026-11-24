package com.threadfit.glucopilot.meal.suitability;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule;
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
 * 适食引擎单测（V94 per100gGL 口径）：
 * ITEM 优先级与边界、份量修正不越界、MEAL 碳水占比聚合、重点关注标记、确定性。
 * 规则集与 V94 种子保持同值（GL_MODERATE priority 10 < GL_LOW 20 < GL_HIGH 30）。
 */
class SuitabilityRuleEngineTest {

    private final SuitabilityRuleEngine engine = new SuitabilityRuleEngine();

    // ===== ITEM 阶段：per100gGL 分档 =====

    @Test
    void lowGlHitsLowRule() {
        // 西瓜：GI 72 × 每100g碳水 6g / 100 = 4.32 → LOW（高 GI 低密度食物不再被误判）
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(
                glRules(), List.of(glItem("西瓜", "4.32", "40")), null, null);

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_LOW, result.getTier());
        assertEquals(SuitabilityRuleEngine.TIER_LOW, result.getBaseTier());
        assertEquals("GL_ITEM_LOW", result.getRuleCode());
    }

    @Test
    void boundaryTenAndTwentyFallIntoModerate() {
        // 口径：10 ≤ x ≤ 20 → MODERATE（MODERATE priority 最高，消解边界重叠）
        assertEquals("GL_ITEM_MODERATE", engine.evaluate(glRules(),
                List.of(glItem("边界10", "10.0", "30")), null, null)
                .getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_MODERATE", engine.evaluate(glRules(),
                List.of(glItem("边界20", "20.0", "30")), null, null)
                .getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_LOW", engine.evaluate(glRules(),
                List.of(glItem("边界9.9", "9.9", "30")), null, null)
                .getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_HIGH", engine.evaluate(glRules(),
                List.of(glItem("边界20.1", "20.1", "30")), null, null)
                .getItems().get(0).getRuleCode());
    }

    @Test
    void ruleOrderDoesNotDependOnInputOrder() {
        List<FoodSuitabilityRule> reversed = new ArrayList<>(glRules());
        Collections.reverse(reversed);

        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(
                reversed, List.of(glItem("米饭", "21.6", "45")), null, null);

        assertEquals("GL_ITEM_HIGH", evaluation.getItems().get(0).getRuleCode());
    }

    @Test
    void missingGlDegradesToUnknown() {
        // UNRESOLVED / 缺 GI / 缺每100g碳水：per100gGL 为空 → UNKNOWN，不用 0 冒充
        MealSuitabilityDtos.ItemInput noGl = new MealSuitabilityDtos.ItemInput();
        noGl.setFoodName("自定义菜");
        noGl.setCarbsG(new BigDecimal("50"));

        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(noGl), null, null);

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, result.getTier());
        assertNull(result.getRuleCode());
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    // ===== 份量修正：量级桶温和修正，不越界 =====

    @Test
    void portionBucketsAdjustModerateBothWays() {
        // per100gGL=12（MODERATE）：碳水 <10g 挪到 LOW；≥60g 挪到 HIGH；中间桶保持
        assertEquals(SuitabilityRuleEngine.TIER_LOW, finalTier(glItem("杂粮少量", "12.0", "8")));
        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, finalTier(glItem("杂粮常量", "12.0", "25")));
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, finalTier(glItem("杂粮大量", "12.0", "65")));
    }

    @Test
    void highNeverDowngradedByPortion() {
        // HIGH 不因份量降档：一小碟（碳水 8g）高 per100gGL 食物仍为 HIGH
        MealSuitabilityDtos.ItemResult result = engine.evaluate(glRules(),
                        List.of(glItem("薯条小碟", "22.5", "8")), portionRules(), null)
                .getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, result.getTier());
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, result.getBaseTier());
    }

    @Test
    void lowNeverUpgradedByPortion() {
        // LOW 默认不升档（预留行 enabled=0 不生效）
        assertEquals(SuitabilityRuleEngine.TIER_LOW, finalTier(glItem("西瓜大量", "4.3", "80")));
    }

    @Test
    void portionAdjustDisabledRowIsIgnored() {
        List<MealPortionAdjustRule> withDisabledLowUpgrade = new ArrayList<>(portionRules());
        withDisabledLowUpgrade.add(adjust("LOW", "60", null, "MODERATE", 30, 0));

        assertEquals(SuitabilityRuleEngine.TIER_LOW, engine.evaluate(glRules(),
                List.of(glItem("西瓜大量", "4.3", "80")), withDisabledLowUpgrade, null)
                .getItems().get(0).getTier());
    }

    @Test
    void weightErrorDoesNotChangeBaseTier() {
        // ±50% 重量误差只影响 carbsG（份量修正），per100gGL 档位（baseTier）不变（验收 2）
        String[] carbVariants = {"15", "22.5", "34"}; // 同一食物重量估算的波动
        String firstBase = null;
        for (String carbs : carbVariants) {
            MealSuitabilityDtos.ItemResult result = engine.evaluate(glRules(),
                            List.of(glItem("燕麦", "11.0", carbs)), null, null)
                    .getItems().get(0);
            if (firstBase == null) {
                firstBase = result.getBaseTier();
            }
            assertEquals(firstBase, result.getBaseTier());
        }
        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, firstBase);
    }

    // ===== MEAL 阶段：碳水占比聚合 =====

    @Test
    void mealTierFollowsDominantCarbShareItem() {
        // 杂粮饭（MODERATE，碳水 40g 中间桶不修正）+ 小碟薯条（HIGH，碳水 8g）：占比最大项定档 → MODERATE
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("杂粮饭", "12.0", "40"),
                glItem("薯条", "22.5", "8")), portionRules(), null);

        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, evaluation.getOverallTier());
        assertEquals("GL_ITEM_MODERATE", evaluation.getWorstRuleCode());
        // 薯条只产生“重点关注”（本身偏高），不把整餐拉为 HIGH（验收 3）
        assertEquals(1, evaluation.getFocusItems().size());
        assertEquals("薯条", evaluation.getFocusItems().get(0).getFoodName());
        assertEquals(MealSuitabilityDtos.FOCUS_INTRINSIC, evaluation.getFocusItems().get(0).getReason());
    }

    @Test
    void focusReasonDistinguishesPortionDrivenHigh() {
        // MODERATE + 碳水 ≥60g 修正为 HIGH → 重点关注原因为“份量偏大”
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("杂粮饭大份", "12.0", "65")), portionRules(), null);

        MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(0);
        assertEquals(SuitabilityRuleEngine.TIER_HIGH, result.getTier());
        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, result.getBaseTier());
        assertTrue(result.getFocused());
        assertEquals(MealSuitabilityDtos.FOCUS_PORTION, result.getFocusReason());
        assertEquals(MealSuitabilityDtos.FOCUS_PORTION, evaluation.getFocusItems().get(0).getReason());
    }

    @Test
    void unknownItemsSkippedInMealAggregation() {
        // UNKNOWN 跳过取下一个占比项
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("手输主食", null, "60"),
                glItem("杂粮饭", "12.0", "30")), portionRules(), null);

        assertEquals(SuitabilityRuleEngine.TIER_MODERATE, evaluation.getOverallTier());
        assertEquals("GL_ITEM_MODERATE", evaluation.getWorstRuleCode());
    }

    @Test
    void carbsMissingItemExcludedFromShareDenominator() {
        // carbsG 缺失项排除出分母与竞选：整餐由唯一有份量的项定档
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("无份量蔬菜", "3.0", null),
                glItem("米饭", "21.6", "45")), portionRules(), null);

        assertEquals(SuitabilityRuleEngine.TIER_HIGH, evaluation.getOverallTier());
        assertEquals("GL_ITEM_HIGH", evaluation.getWorstRuleCode());
    }

    @Test
    void allUnknownMealStaysUnknown() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("手输菜A", null, "30"),
                glItem("手输菜B", null, "20")), portionRules(), null);

        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
        assertTrue(evaluation.getFocusItems().isEmpty());
    }

    @Test
    void shareThresholdFiltersSmallShareItems() {
        // 占比门槛 0.5：占比最大的薯条（8/38≈0.21）被过滤 → 由米饭定档
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("米饭", "21.6", "30"),
                glItem("薯条", "22.5", "8")), portionRules(),
                new SuitabilityRuleEngine.AggregationParams(new BigDecimal("0.5"), true));

        assertEquals(SuitabilityRuleEngine.TIER_HIGH, evaluation.getOverallTier());
    }

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

        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(rules, List.of(dense), null, null);
        assertEquals("CARB_DENSE_MODERATE", evaluation.getItems().get(0).getRuleCode());

        // carbsPer100g 为 null 时 CARB 规则不命中（不用 0 冒充）→ 落到 GL 规则
        MealSuitabilityDtos.Evaluation withoutCarbDensity = engine.evaluate(
                rules, List.of(glItem("无碳水密度", "25.0", "50")), null, null);
        assertEquals("GL_ITEM_HIGH", withoutCarbDensity.getItems().get(0).getRuleCode());
    }

    @Test
    void sameInputProducesSameResult() {
        List<MealSuitabilityDtos.ItemInput> items = List.of(
                glItem("米饭", "21.6", "45"),
                glItem("西瓜", "4.32", "40"),
                glItem("手输菜", null, "20"));

        MealSuitabilityDtos.Evaluation first = engine.evaluate(glRules(), items, portionRules(), null);
        MealSuitabilityDtos.Evaluation second = engine.evaluate(glRules(), items, portionRules(), null);

        assertEquals(first.getOverallTier(), second.getOverallTier());
        assertEquals(first.getWorstRuleCode(), second.getWorstRuleCode());
        for (int i = 0; i < first.getItems().size(); i++) {
            assertEquals(first.getItems().get(i).getTier(), second.getItems().get(i).getTier());
            assertEquals(first.getItems().get(i).getRuleCode(), second.getItems().get(i).getRuleCode());
        }
    }

    @Test
    void emptyItemsStaysUnknown() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(), portionRules(), null);

        assertTrue(evaluation.getItems().isEmpty());
        assertEquals(SuitabilityRuleEngine.TIER_UNKNOWN, evaluation.getOverallTier());
        assertNull(evaluation.getWorstRuleCode());
    }

    @Test
    void noFocusWhenNoHighItem() {
        MealSuitabilityDtos.Evaluation evaluation = engine.evaluate(glRules(), List.of(
                glItem("西瓜", "4.32", "40")), portionRules(), null);

        assertFalse(Boolean.TRUE.equals(evaluation.getItems().get(0).getFocused()));
        assertTrue(evaluation.getFocusItems().isEmpty());
    }

    // ===== 测试数据（与 V94 种子同值） =====

    /** V94 新版本规则：三档边界重叠由 priority 消解（MODERATE=10 < LOW=20 < HIGH=30）。 */
    private static List<FoodSuitabilityRule> glRules() {
        return List.of(
                glRule("GL_ITEM_MODERATE", "10.0", "20.0", "MODERATE", 10),
                glRule("GL_ITEM_LOW", null, "10.0", "LOW", 20),
                glRule("GL_ITEM_HIGH", "20.0", null, "HIGH", 30));
    }

    /** V94 份量修正种子：MODERATE 上下各挪一档；LOW+大份预留行默认禁用。 */
    private static List<MealPortionAdjustRule> portionRules() {
        return List.of(
                adjust("MODERATE", null, "10.0", "LOW", 10, 1),
                adjust("MODERATE", "60.0", null, "HIGH", 20, 1));
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

    private static MealPortionAdjustRule adjust(String baseTier, String min, String max,
                                                String adjustedTier, int priority, int enabled) {
        MealPortionAdjustRule rule = new MealPortionAdjustRule();
        rule.setBaseTier(baseTier);
        rule.setCarbBucketMin(min == null ? null : new BigDecimal(min));
        rule.setCarbBucketMax(max == null ? null : new BigDecimal(max));
        rule.setAdjustedTier(adjustedTier);
        rule.setPriority(priority);
        rule.setIsEnabled(enabled);
        return rule;
    }

    private static MealSuitabilityDtos.ItemInput glItem(String foodName, String per100gGl, String carbsG) {
        MealSuitabilityDtos.ItemInput item = new MealSuitabilityDtos.ItemInput();
        item.setFoodName(foodName);
        item.setPer100gGl(per100gGl == null ? null : new BigDecimal(per100gGl));
        item.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        return item;
    }

    private String finalTier(MealSuitabilityDtos.ItemInput item) {
        return engine.evaluate(glRules(), List.of(item), portionRules(), null)
                .getItems().get(0).getTier();
    }
}
