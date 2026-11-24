package com.threadfit.glucopilot.meal.suitability;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.suitability.service.SuitabilityConfigService;
import com.threadfit.glucopilot.meal.suitability.service.impl.MealSuitabilityServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 预览响应组装层：验证「判定依据 = 返回给前端的明细」与 preview/save 共用文案源。
 * <p>引擎与归一化组件均为纯逻辑，这里用 mock 隔离，只锁响应层回填行为。
 */
@ExtendWith(MockitoExtension.class)
class MealSuitabilityServiceImplTest {

    @Mock private FoodSuitabilityRuleMapper ruleMapper;
    @Mock private MealNutritionResolver nutritionResolver;
    @Mock private SuitabilityConfigService configService;
    @InjectMocks private MealSuitabilityServiceImpl service;

    private static final Map<String, String> CONFIG = Map.of(
            "advice_low", "升糖负担低，可按常量食用",
            "advice_high", "升糖负担高，建议少量或替换",
            "advice_unknown", "缺少 GI 或碳水信息，暂无法判断{foodName}的升糖负担");

    @Test
    void previewExposesNormalizedValuesAndPer100gGl() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(glHighRule(null)));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                resolvedItem("米饭", "21.55", "45", new BigDecimal("83.2"))));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenReturn(SuitabilityRuleEngine.AggregationParams.defaults());

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        // 整餐由碳水占比最大项（唯一项）定档；worstRuleCode 字段名保留，语义为驱动结论的规则
        assertEquals("HIGH", response.getTier());
        assertEquals("GL_ITEM_HIGH", response.getWorstRuleCode());
        MealSuitabilityDtos.PreviewItem item = response.getItems().get(0);
        // 关键：返回给前端的明细必须是归一化后的值（库补全），前端据此解释 HIGH 结论
        assertEquals(new BigDecimal("83.2"), item.getGiValue());
        assertEquals(new BigDecimal("21.55"), item.getPer100gGl());
        assertEquals("HIGH", item.getTier());
        assertEquals("HIGH", item.getBaseTier());
        assertEquals("GL_ITEM_HIGH", item.getRuleCode());
        assertEquals("CLIENT", item.getGiSource());
        // 米饭 per100gGL 偏高 → 重点关注（本身偏高），不改整餐档位
        assertEquals(1, response.getFocusItems().size());
        assertEquals("米饭", response.getFocusItems().get(0).getFoodName());
        assertEquals("INTRINSIC", response.getFocusItems().get(0).getReason());
    }

    @Test
    void requestItemsAreResolvedInPreviewMode() {
        MealSuitabilityDtos.PreviewRequest request = request(null);
        when(ruleMapper.selectList(any())).thenReturn(List.of(glHighRule(null)));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                resolvedItem("米饭", "21.55", "45", new BigDecimal("83.2"))));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenReturn(SuitabilityRuleEngine.AggregationParams.defaults());

        service.preview(1L, request);

        // preview 必须是「客户端值优先」，不能走保存路径的库值覆盖
        org.mockito.Mockito.verify(nutritionResolver).resolve(request.getItems(), true);
    }

    @Test
    void unknownRuleVersionIsStillBizRejected() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(glHighRule(null)));
        MealSuitabilityDtos.PreviewRequest request = request("not-exists");

        BizException ex = assertThrows(BizException.class, () -> service.preview(1L, request));

        assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
        assertEquals(40001, ex.getErrorCode().getCode());
    }

    @Test
    void previewFillsRuleAdviceTextWithPlaceholders() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                glHighRule("{foodName}每100g升糖负荷偏高，建议少量或替换")));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                resolvedItem("米饭", "21.55", "45", new BigDecimal("83.2"))));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenReturn(SuitabilityRuleEngine.AggregationParams.defaults());

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        // 规则配置了文案时优先使用规则文案（{foodName} 已替换）
        assertEquals("米饭每100g升糖负荷偏高，建议少量或替换",
                response.getItems().get(0).getAdviceText());
    }

    @Test
    void adviceFallsBackToTierPoolWhenRuleHasNoText() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(glHighRule(null)));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                resolvedItem("米饭", "21.55", "45", new BigDecimal("83.2"))));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenReturn(SuitabilityRuleEngine.AggregationParams.defaults());

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        // 规则未配文案时走配置候选池（与 save 同源），保证前后端文案一致
        assertEquals("升糖负担高，建议少量或替换",
                response.getItems().get(0).getAdviceText());
    }

    @Test
    void unknownTierUsesUnknownAdviceAndMissingFields() {
        // UNRESOLVED：per100gGL 为空 → UNKNOWN，展示缺失字段，走 UNKNOWN 兜底池
        when(ruleMapper.selectList(any())).thenReturn(List.of(glHighRule(null)));
        MealSuitabilityDtos.ItemInput unresolved = resolvedItem("自定义菜", null, "50", null);
        unresolved.setMissingFields(List.of("食品库未匹配", "GI", "每100g碳水"));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(unresolved));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenReturn(SuitabilityRuleEngine.AggregationParams.defaults());

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        MealSuitabilityDtos.PreviewItem item = response.getItems().get(0);
        assertEquals("UNKNOWN", item.getTier());
        assertNull(item.getRuleCode());
        assertEquals(List.of("食品库未匹配", "GI", "每100g碳水"), item.getMissingFields());
        assertEquals("缺少 GI 或碳水信息，暂无法判断自定义菜的升糖负担", item.getAdviceText());
        // 全 UNKNOWN → 整餐 UNKNOWN，无重点关注
        assertEquals("UNKNOWN", response.getTier());
        assertNull(response.getWorstRuleCode());
        assertEquals(0, response.getFocusItems().size());
    }

    @Test
    void portionDrivenHighItemIsFlaggedWithFocusText() {
        // MODERATE + 碳水 ≥60g 修正为 HIGH → 重点关注原因为「份量偏大」，文案取配置
        when(ruleMapper.selectList(any())).thenReturn(List.of(glModerateRule()));
        when(nutritionResolver.resolve(anyList(), eq(true))).thenReturn(List.of(
                resolvedItem("杂粮饭大份", "12.0", "65", new BigDecimal("55"))));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of(portionRule("MODERATE", "60", null, "HIGH")));
        when(configService.aggregationParams(any())).thenReturn(SuitabilityRuleEngine.AggregationParams.defaults());
        when(configService.focusText(eq(CONFIG), eq("PORTION"), eq("杂粮饭大份")))
                .thenReturn("重点关注：杂粮饭大份 份量偏大，升糖负担高");

        MealSuitabilityDtos.PreviewResponse response = service.preview(1L, request(null));

        assertEquals("HIGH", response.getItems().get(0).getTier());
        assertEquals("MODERATE", response.getItems().get(0).getBaseTier());
        assertEquals("PORTION", response.getItems().get(0).getFocusReason());
        assertEquals("重点关注：杂粮饭大份 份量偏大，升糖负担高",
                response.getFocusItems().get(0).getText());
    }

    private MealSuitabilityDtos.PreviewRequest request(String ruleVersion) {
        MealSuitabilityDtos.ItemInput raw = new MealSuitabilityDtos.ItemInput();
        raw.setFoodName("米饭");
        MealSuitabilityDtos.PreviewRequest request = new MealSuitabilityDtos.PreviewRequest();
        request.setNodeCode("BREAKFAST");
        request.setRuleVersion(ruleVersion);
        request.setItems(List.of(raw));
        return request;
    }

    /** resolver 归一化后的判定输入。 */
    private static MealSuitabilityDtos.ItemInput resolvedItem(String name, String per100gGl,
                                                              String carbsG, BigDecimal gi) {
        MealSuitabilityDtos.ItemInput item = new MealSuitabilityDtos.ItemInput();
        item.setFoodName(name);
        item.setPer100gGl(per100gGl == null ? null : new BigDecimal(per100gGl));
        item.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        item.setGiValue(gi);
        item.setGiSource(gi == null ? null : "CLIENT");
        item.setLibraryStatus("AVAILABLE");
        item.setNutritionSource("LIBRARY");
        return item;
    }

    private static FoodSuitabilityRule glHighRule(String adviceText) {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode("GL_ITEM_HIGH");
        rule.setMetric("GL");
        rule.setScope("ITEM");
        rule.setGlMin(new BigDecimal("20"));
        rule.setTier("HIGH");
        rule.setPriority(30);
        rule.setRuleVersion("2026.11.1");
        rule.setIsEnabled(1);
        rule.setAdviceText(adviceText);
        return rule;
    }

    private static FoodSuitabilityRule glModerateRule() {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode("GL_ITEM_MODERATE");
        rule.setMetric("GL");
        rule.setScope("ITEM");
        rule.setGlMin(new BigDecimal("10"));
        rule.setGlMax(new BigDecimal("20"));
        rule.setTier("MODERATE");
        rule.setPriority(10);
        rule.setRuleVersion("2026.11.1");
        rule.setIsEnabled(1);
        return rule;
    }

    private static com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule portionRule(
            String baseTier, String min, String max, String adjustedTier) {
        com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule rule =
                new com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule();
        rule.setBaseTier(baseTier);
        rule.setCarbBucketMin(min == null ? null : new BigDecimal(min));
        rule.setCarbBucketMax(max == null ? null : new BigDecimal(max));
        rule.setAdjustedTier(adjustedTier);
        rule.setPriority(20);
        rule.setIsEnabled(1);
        return rule;
    }
}
