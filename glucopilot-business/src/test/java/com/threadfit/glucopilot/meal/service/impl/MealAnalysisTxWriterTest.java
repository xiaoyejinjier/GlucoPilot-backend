package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.support.MealAnalysisTexts;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 分析落库：规则版本筛选、单项建议写回与整餐结论。 */
@ExtendWith(MockitoExtension.class)
class MealAnalysisTxWriterTest {

    @Mock
    private FoodSuitabilityRuleMapper ruleMapper;
    @Mock
    private MealRecordMapper mealRecordMapper;
    @Mock
    private MealFoodItemMapper mealFoodItemMapper;

    @InjectMocks
    private MealAnalysisTxWriter writer;

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
    void writesAnalysisColumnsAndItemAdvice() {
        MealFoodItem rice = food(1L, "米饭", 1, "83.2");
        MealFoodItem greens = food(2L, "青菜", 3, "15.0");
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(rice, greens));
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule("GI_HIGH_70", null, "70", null, "HIGH", 5, "v1"),
                rule("CAT3_LOW", 3, null, null, "LOW", 10, "v1")));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        assertEquals("HIGH", analysis.getTier());
        assertEquals("升糖负担较高", analysis.getTierLabel());
        assertEquals("v1", analysis.getRuleVersion());
        assertEquals(2, analysis.getItems().size());
        assertEquals("GI_HIGH_70", analysis.getItems().get(0).getRuleCode());
        assertEquals("CAT3_LOW", analysis.getItems().get(1).getRuleCode());

        ArgumentCaptor<MealFoodItem> items = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper, org.mockito.Mockito.times(2)).updateById(items.capture());
        assertEquals("HIGH", items.getAllValues().get(0).getAdviceTier());
        assertEquals("GI_HIGH_70", items.getAllValues().get(0).getAdviceReasonCode());
        assertEquals(MealAnalysisTexts.adviceOf("HIGH"), items.getAllValues().get(0).getAdviceText());

        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mealRecordMapper).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_status"));
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_tier"));
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_rule_version"));
    }

    @Test
    void picksRulesOfLatestVersionOnly() {
        MealFoodItem rice = food(1L, "米饭", 1, null);
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(rice));
        // 旧版本里有一条更高优先级的 LOW，若未按版本过滤会误命中它
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule("CAT1_LOW_OLD", 1, null, null, "LOW", 1, "v1"),
                rule("CAT1_HIGH_NEW", 1, null, null, "HIGH", 50, "v2")));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        assertEquals("v2", analysis.getRuleVersion());
        assertEquals("HIGH", analysis.getTier());
        assertEquals("CAT1_HIGH_NEW", analysis.getItems().getFirst().getRuleCode());
    }

    @Test
    void unmatchedItemIsUnknownAndNotWrittenWithRuleCode() {
        MealFoodItem mystery = food(1L, "未知食物", 9, null);
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(mystery));
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule("CAT3_LOW", 3, null, null, "LOW", 10, "v1")));

        MealVos.Analysis analysis = writer.write(1L, 2L);

        assertEquals("UNKNOWN", analysis.getTier());
        assertEquals("信息不足", analysis.getTierLabel());
        assertNull(analysis.getItems().getFirst().getRuleCode());
        ArgumentCaptor<MealFoodItem> item = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper).updateById(item.capture());
        assertEquals("UNKNOWN", item.getValue().getAdviceTier());
        assertNull(item.getValue().getAdviceReasonCode());
    }

    @Test
    void throwsWhenNoEnabledRule() {
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(food(1L, "米饭", 1, null)));
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> writer.write(1L, 2L));

        // 失败不得写任何分析列，交由调用方标记 FAILED
        verify(mealRecordMapper, never()).update(any(), any());
        verify(mealFoodItemMapper, never()).updateById(any(MealFoodItem.class));
    }

    private static MealFoodItem food(Long id, String name, Integer category, String gi) {
        MealFoodItem row = new MealFoodItem();
        row.setId(id);
        row.setMealId(2L);
        row.setFoodName(name);
        row.setCategoryCode(category);
        row.setGiValue(gi == null ? null : new BigDecimal(gi));
        return row;
    }

    private static FoodSuitabilityRule rule(
            String code, Integer category, String giMin, String giMax, String tier, int priority,
            String version) {
        FoodSuitabilityRule rule = new FoodSuitabilityRule();
        rule.setRuleCode(code);
        rule.setCategoryCode(category);
        rule.setGiMin(giMin == null ? null : new BigDecimal(giMin));
        rule.setGiMax(giMax == null ? null : new BigDecimal(giMax));
        rule.setTier(tier);
        rule.setPriority(priority);
        rule.setRuleVersion(version);
        rule.setIsEnabled(1);
        return rule;
    }
}
