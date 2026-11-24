package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.suitability.service.SuitabilityConfigService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分析落库（V94）：save 路径整体经 resolver 归一化、建议走 AdviceResolver、
 * 重点关注清单落 analysis_focus、规则版本筛选与失败不写列。
 */
@ExtendWith(MockitoExtension.class)
class MealAnalysisTxWriterTest {

    @Mock
    private FoodSuitabilityRuleMapper ruleMapper;
    @Mock
    private MealRecordMapper mealRecordMapper;
    @Mock
    private MealFoodItemMapper mealFoodItemMapper;
    @Mock
    private MealNutritionResolver nutritionResolver;
    @Mock
    private SuitabilityConfigService configService;

    @InjectMocks
    private MealAnalysisTxWriter writer;

    private static final Map<String, String> CONFIG = Map.of(
            "advice_low", "升糖负担低，可按常量食用",
            "advice_high", "升糖负担高，建议少量或替换",
            "advice_unknown", "缺少 GI 或碳水信息，暂无法判断{foodName}的升糖负担",
            "focus_text_intrinsic", "重点关注：{foodName} 升糖负担高，建议少量或替换",
            "focus_text_portion", "重点关注：{foodName} 份量偏大，升糖负担高");

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MealRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MealFoodItem.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), FoodSuitabilityRule.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesAnalysisColumnsItemAdviceAndFocus() {
        // 米饭：per100gGL 21.6（HIGH，碳水 45g）；青菜：per100gGL 3.0（LOW，碳水 5g）
        MealFoodItem rice = food(1L, "米饭", "21.6", "45");
        MealFoodItem greens = food(2L, "青菜", "3.0", "5");
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(rice, greens));
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                glRule("GL_ITEM_MODERATE", "10.0", "20.0", "MODERATE", 10, "2026.11.1"),
                glRule("GL_ITEM_LOW", null, "10.0", "LOW", 20, "2026.11.1"),
                glRule("GL_ITEM_HIGH", "20.0", null, "HIGH", 30, "2026.11.1")));
        when(nutritionResolver.resolve(any(), eq(false)))
                .thenReturn(List.of(resolved("米饭", "21.6", "45"), resolved("青菜", "3.0", "5")));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenAnswer(
                invocation -> new com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine
                        .AggregationParams(BigDecimal.ZERO, true));
        when(configService.focusText(eq(CONFIG), eq("INTRINSIC"), eq("米饭")))
                .thenReturn("重点关注：米饭 升糖负担高，建议少量或替换");

        MealVos.Analysis analysis = writer.write(1L, 2L);

        // 整餐：碳水占比最大项（米饭 45/50）定档 HIGH；米饭为重点关注（本身偏高）
        assertEquals("HIGH", analysis.getTier());
        assertEquals("升糖负担较高", analysis.getTierLabel());
        assertEquals("2026.11.1", analysis.getRuleVersion());
        assertEquals(2, analysis.getItems().size());
        assertEquals("GL_ITEM_HIGH", analysis.getItems().get(0).getRuleCode());
        assertEquals("GL_ITEM_LOW", analysis.getItems().get(1).getRuleCode());
        assertEquals(1, analysis.getFocusItems().size());
        assertEquals("米饭", analysis.getFocusItems().get(0).getFoodName());
        assertEquals("INTRINSIC", analysis.getFocusItems().get(0).getReason());
        assertEquals("重点关注：米饭 升糖负担高，建议少量或替换", analysis.getFocusItems().get(0).getText());

        // 单项建议：规则 advice_text（占位符已替换）
        ArgumentCaptor<MealFoodItem> items = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper, times(2)).updateById(items.capture());
        assertEquals("HIGH", items.getAllValues().get(0).getAdviceTier());
        assertEquals("GL_ITEM_HIGH", items.getAllValues().get(0).getAdviceReasonCode());
        assertEquals("米饭每100g升糖负荷偏高，建议少量或替换",
                items.getAllValues().get(0).getAdviceText());

        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mealRecordMapper).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_status"));
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_tier"));
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_focus"));
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_rule_version"));
    }

    @Test
    void noHighItemWritesNullFocus() {
        // 全 LOW：analysis_focus 落 NULL，focusItems 为空
        MealFoodItem greens = food(1L, "青菜", "3.0", "5");
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(greens));
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                glRule("GL_ITEM_LOW", null, "10.0", "LOW", 20, "2026.11.1")));
        when(nutritionResolver.resolve(any(), eq(false)))
                .thenReturn(List.of(resolved("青菜", "3.0", "5")));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenAnswer(
                invocation -> new com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine
                        .AggregationParams(BigDecimal.ZERO, true));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        assertEquals("LOW", analysis.getTier());
        assertTrue(analysis.getFocusItems().isEmpty());
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mealRecordMapper).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_focus"));
    }

    @Test
    void savePathResolvesThroughNutritionResolver() {
        // 验收 4：save 路径经 resolver（clientValuesFirst=false）拿 carbsPer100g/per100gGL，不是 toInput 补 carbsG
        MealFoodItem rice = food(1L, "米饭", null, "45");
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(rice));
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                glRule("GL_ITEM_HIGH", "20.0", null, "HIGH", 30, "2026.11.1")));
        // resolver 归一化后回填 per100gGL（模拟库回查结果）
        when(nutritionResolver.resolve(any(), eq(false)))
                .thenReturn(List.of(resolved("米饭", "21.6", "45")));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenAnswer(
                invocation -> new com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine
                        .AggregationParams(BigDecimal.ZERO, true));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        verify(nutritionResolver).resolve(any(), eq(false));
        verify(nutritionResolver, never()).resolve(any(), eq(true));
        assertEquals("GL_ITEM_HIGH", analysis.getItems().getFirst().getRuleCode());
    }

    @Test
    void picksRulesOfLatestVersionOnly() {
        MealFoodItem rice = food(1L, "米饭", "21.6", "45");
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(rice));
        // 旧版本里有一条更高优先级的 MODERATE，若未按版本过滤会误命中它
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                glRule("GL_ITEM_MODERATE_OLD", "10.0", "20.0", "MODERATE", 1, "2026.11.0"),
                glRule("GL_ITEM_HIGH_NEW", "20.0", null, "HIGH", 50, "2026.11.2")));
        when(nutritionResolver.resolve(any(), eq(false)))
                .thenReturn(List.of(resolved("米饭", "21.6", "45")));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenAnswer(
                invocation -> new com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine
                        .AggregationParams(BigDecimal.ZERO, true));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        assertEquals("2026.11.2", analysis.getRuleVersion());
        assertEquals("GL_ITEM_HIGH_NEW", analysis.getItems().getFirst().getRuleCode());
    }

    @Test
    void unmatchedItemIsUnknownWithoutRuleCode() {
        MealFoodItem mystery = food(1L, "未知食物", null, "50");
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(mystery));
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(glRule("GL_ITEM_HIGH", "20.0", null, "HIGH", 30, "2026.11.1")));
        // resolver 归一化后仍无 per100gGL（UNRESOLVED）
        MealSuitabilityDtos.ItemInput unresolved = resolved("未知食物", null, "50");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(unresolved));
        when(configService.configMap()).thenReturn(CONFIG);
        when(configService.adjustRules()).thenReturn(List.of());
        when(configService.aggregationParams(any())).thenAnswer(
                invocation -> new com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine
                        .AggregationParams(BigDecimal.ZERO, true));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        assertEquals("UNKNOWN", analysis.getTier());
        assertEquals("信息不足", analysis.getTierLabel());
        assertNull(analysis.getItems().getFirst().getRuleCode());
        ArgumentCaptor<MealFoodItem> item = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper).updateById(item.capture());
        assertEquals("UNKNOWN", item.getValue().getAdviceTier());
        assertNull(item.getValue().getAdviceReasonCode());
        // UNKNOWN 走档位兜底池（AdviceResolver），MealAnalysisTexts 不再有建议 map
        assertEquals("缺少 GI 或碳水信息，暂无法判断未知食物的升糖负担",
                item.getValue().getAdviceText());
    }

    @Test
    void throwsWhenNoEnabledRule() {
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(food(1L, "米饭", "21.6", "45")));
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> writer.write(1L, 2L));

        // 失败不得写任何分析列，交由调用方标记 FAILED
        verify(mealRecordMapper, never()).update(any(), any());
        verify(mealFoodItemMapper, never()).updateById(any(MealFoodItem.class));
    }

    private static MealFoodItem food(Long id, String name, String per100gGl, String carbsG) {
        MealFoodItem row = new MealFoodItem();
        row.setId(id);
        row.setMealId(2L);
        row.setFoodName(name);
        row.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        return row;
    }

    /** resolver 归一化后的判定输入（per100gGL 已回填）。 */
    private static MealSuitabilityDtos.ItemInput resolved(String name, String per100gGl, String carbsG) {
        MealSuitabilityDtos.ItemInput input = new MealSuitabilityDtos.ItemInput();
        input.setFoodName(name);
        input.setPer100gGl(per100gGl == null ? null : new BigDecimal(per100gGl));
        input.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        return input;
    }

    private static FoodSuitabilityRule glRule(
            String code, String glMin, String glMax, String tier, int priority, String version) {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode(code);
        rule.setMetric("GL");
        rule.setScope("ITEM");
        rule.setGlMin(glMin == null ? null : new BigDecimal(glMin));
        rule.setGlMax(glMax == null ? null : new BigDecimal(glMax));
        rule.setTier(tier);
        rule.setPriority(priority);
        rule.setRuleVersion(version);
        rule.setIsEnabled(1);
        if ("GL_ITEM_HIGH".equals(code)) {
            rule.setAdviceText("{foodName}每100g升糖负荷偏高，建议少量或替换");
        }
        return rule;
    }
}
