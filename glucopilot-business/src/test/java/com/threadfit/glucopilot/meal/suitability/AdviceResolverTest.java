package com.threadfit.glucopilot.meal.suitability;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.suitability.engine.AdviceResolver;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdviceResolver 单测：规则文案优先、占位符替换、候选池轮换（避免多项同话）、
 * 同一输入产出一致文案（preview 与 save 共用同一纯函数的验收基础）。
 */
class AdviceResolverTest {

    private static final Map<String, String> TEXTS = Map.of(
            "advice_low", "升糖负担低，可按常量食用|升糖负担低，注意整体搭配即可",
            "advice_high", "升糖负担高，建议少量或替换",
            "advice_unknown", "缺少 GI 或碳水信息，暂无法判断{foodName}的升糖负担");

    @Test
    void ruleAdviceTextTakesPriority() {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode("GL_ITEM_HIGH");
        rule.setAdviceText("{foodName}每100g升糖负荷偏高，建议少量或替换");

        String advice = AdviceResolver.resolve(rule, "HIGH", item("薯条", "8"), 0, TEXTS);

        assertEquals("薯条每100g升糖负荷偏高，建议少量或替换", advice);
    }

    @Test
    void placeholdersAreReplaced() {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setAdviceText("{foodName}碳水约{carbG}，GL约{glValue}，建议控制");

        ItemInput item = item("米饭", "45");
        item.setPer100gGl(new BigDecimal("21.60"));

        String advice = AdviceResolver.resolve(rule, "HIGH", item, 0, TEXTS);

        assertEquals("米饭碳水约45g，GL约21.6，建议控制", advice);
    }

    @Test
    void fallbackPoolRotatesByItemIndex() {
        // 同档位多项：候选池按序号轮换，避免同话
        ItemInput first = item("西瓜", "5");
        ItemInput second = item("圣女果", "4");

        String firstAdvice = AdviceResolver.resolve(null, "LOW", first, 0, TEXTS);
        String secondAdvice = AdviceResolver.resolve(null, "LOW", second, 1, TEXTS);

        assertEquals("升糖负担低，可按常量食用", firstAdvice);
        assertEquals("升糖负担低，注意整体搭配即可", secondAdvice);
        // 序号回绕
        assertEquals(firstAdvice, AdviceResolver.resolve(null, "LOW", first, 2, TEXTS));
    }

    @Test
    void sameInputProducesSameAdvice() {
        // preview/save 一致性验收基础：同一 (rule, item, index, texts) → 同一文案
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode("GL_ITEM_HIGH");
        rule.setAdviceText("{foodName}每100g升糖负荷偏高，建议少量或替换");

        assertEquals(
                AdviceResolver.resolve(rule, "HIGH", item("薯条", "8"), 0, TEXTS),
                AdviceResolver.resolve(rule, "HIGH", item("薯条", "8"), 0, TEXTS));
    }

    @Test
    void unknownFallbackSupportsFoodNamePlaceholder() {
        String advice = AdviceResolver.resolve(null, "UNKNOWN", item("自定义菜", null), 0, TEXTS);
        assertEquals("缺少 GI 或碳水信息，暂无法判断自定义菜的升糖负担", advice);
    }

    @Test
    void missingTextsReturnsNull() {
        FoodSuitabilityRule noAdviceRule = new FoodSuitabilityRule();
        noAdviceRule.setRuleCode("GL_ITEM_MODERATE");

        assertNull(AdviceResolver.resolve(noAdviceRule, "MODERATE", item("杂粮饭", "25"), 0, TEXTS));
        assertNull(AdviceResolver.resolve(null, "MODERATE", item("杂粮饭", "25"), 0, Map.of()));
        assertNull(AdviceResolver.resolve(null, null, item("杂粮饭", "25"), 0, TEXTS));
    }

    @Test
    void blankRuleAdviceFallsBackToPool() {
        FoodSuitabilityRule blankRule = new FoodSuitabilityRule();
        blankRule.setRuleCode("GL_ITEM_HIGH");
        blankRule.setAdviceText("   ");

        String advice = AdviceResolver.resolve(blankRule, "HIGH", item("薯条", "8"), 0, TEXTS);
        assertEquals("升糖负担高，建议少量或替换", advice);
    }

    @Test
    void placeholderKeptWhenValueMissing() {
        // 值缺失时保留占位符，由前端兜底
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setAdviceText("{foodName}碳水约{carbG}，建议控制");

        String advice = AdviceResolver.resolve(rule, "HIGH", new ItemInput(), 0, new HashMap<>());
        assertTrue(advice.contains("{carbG}"));
    }

    private static ItemInput item(String foodName, String carbsG) {
        ItemInput item = new ItemInput();
        item.setFoodName(foodName);
        item.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        return item;
    }
}
