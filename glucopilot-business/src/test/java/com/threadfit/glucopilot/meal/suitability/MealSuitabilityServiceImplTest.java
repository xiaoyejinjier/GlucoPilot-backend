package com.threadfit.glucopilot.meal.suitability;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.suitability.service.impl.MealSuitabilityServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预览响应组装层：验证「判定依据 = 返回给前端的明细」。
 * <p>引擎与归一化组件均为纯逻辑，这里用 mock 隔离，只锁响应层回填行为。
 */
@ExtendWith(MockitoExtension.class)
class MealSuitabilityServiceImplTest {

    @Mock private FoodSuitabilityRuleMapper ruleMapper;
    @Mock private MealNutritionResolver nutritionResolver;
    @InjectMocks private MealSuitabilityServiceImpl service;

    @Test
    void previewExposesNormalizedGiAndStatusInItems() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(giHighRule()));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                item("米饭", 1, new BigDecimal("83.2"), "AVAILABLE", "LIBRARY")));

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        assertEquals("HIGH", response.getTier());
        assertEquals("GI_HIGH_70", response.getWorstRuleCode());
        MealSuitabilityDtos.PreviewItem item = response.getItems().get(0);
        // 关键：返回给前端的 giValue 必须是归一化后（库补全）的值，前端据此解释 HIGH 结论
        assertEquals(new BigDecimal("83.2"), item.getGiValue());
        assertEquals(1, item.getCategoryCode());
        assertEquals("AVAILABLE", item.getLibraryStatus());
        assertEquals("LIBRARY", item.getNutritionSource());
        assertEquals("HIGH", item.getTier());
        assertEquals("GI_HIGH_70", item.getRuleCode());
    }

    @Test
    void requestItemsAreResolvedInPreviewMode() {
        MealSuitabilityDtos.PreviewRequest request = request(null);
        when(ruleMapper.selectList(any())).thenReturn(List.of(giHighRule()));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                item("米饭", 1, new BigDecimal("83.2"), "AVAILABLE", "LIBRARY")));

        service.preview(1L, request);

        // preview 必须是「客户端值优先」，不能走保存路径的库值覆盖
        verify(nutritionResolver).resolve(request.getItems(), true);
    }

    @Test
    void unknownRuleVersionIsStillBizRejected() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(giHighRule()));
        MealSuitabilityDtos.PreviewRequest request = request("not-exists");

        BizException ex = assertThrows(BizException.class, () -> service.preview(1L, request));

        assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
        assertEquals(40001, ex.getErrorCode().getCode());
    }

    @Test
    void previewFillsRuleAdviceText() {
        FoodSuitabilityRule rule = giHighRule();
        rule.setAdviceText("升糖速度偏快，建议减少份量或替换为低升糖食物");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                item("米饭", 1, new BigDecimal("83.2"), "AVAILABLE", "LIBRARY")));

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        // 规则配置了文案时优先使用规则文案
        assertEquals("升糖速度偏快，建议减少份量或替换为低升糖食物",
                response.getItems().get(0).getAdviceText());
    }

    @Test
    void adviceFallsBackToTierWhenRuleHasNoText() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(giHighRule()));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                item("米饭", 1, new BigDecimal("83.2"), "AVAILABLE", "LIBRARY")));

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        // 占位规则未配文案时，按档位兜底，保证前端一定有可展示的建议
        assertEquals("升糖负担较高，建议减少份量或替换为低升糖食物",
                response.getItems().get(0).getAdviceText());
    }

    @Test
    void unknownTierUsesUnknownAdvice() {
        // 奶类（5）当前没有匹配规则 → UNKNOWN，走信息不足兜底文案
        when(ruleMapper.selectList(any())).thenReturn(List.of(giHighRule()));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                item("牛奶", 5, null, "AVAILABLE", "LIBRARY")));

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        MealSuitabilityDtos.PreviewItem item = response.getItems().get(0);
        assertEquals("UNKNOWN", item.getTier());
        assertNull(item.getRuleCode());
        assertEquals("信息不足，建议补充重量或改用食品库食物", item.getAdviceText());
    }

    private MealSuitabilityDtos.PreviewRequest request(String ruleVersion) {
        MealSuitabilityDtos.ItemInput raw = new MealSuitabilityDtos.ItemInput();
        raw.setFoodName("米饭");
        raw.setCategoryCode(1);
        MealSuitabilityDtos.PreviewRequest request = new MealSuitabilityDtos.PreviewRequest();
        request.setNodeCode("BREAKFAST");
        request.setRuleVersion(ruleVersion);
        request.setItems(List.of(raw));
        return request;
    }

    private MealSuitabilityDtos.ItemInput item(String name, int category, BigDecimal gi,
                                               String libraryStatus, String source) {
        MealSuitabilityDtos.ItemInput item = new MealSuitabilityDtos.ItemInput();
        item.setFoodName(name);
        item.setCategoryCode(category);
        item.setGiValue(gi);
        item.setLibraryStatus(libraryStatus);
        item.setNutritionSource(source);
        return item;
    }

    private FoodSuitabilityRule giHighRule() {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode("GI_HIGH_70");
        rule.setGiMin(new BigDecimal("70"));
        rule.setTier("HIGH");
        rule.setPriority(5);
        rule.setRuleVersion("2026.11.0-placeholder");
        rule.setIsEnabled(1);
        return rule;
    }
}
