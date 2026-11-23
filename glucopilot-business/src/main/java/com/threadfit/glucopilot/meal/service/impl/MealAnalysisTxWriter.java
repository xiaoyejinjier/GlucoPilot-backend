package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.engine.SuitabilityRuleEngine;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.mapper.FoodSuitabilityRuleMapper;
import com.threadfit.glucopilot.meal.support.MealAnalysisTexts;
import com.threadfit.glucopilot.meal.vo.MealVos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分析落库的独立事务：读取已提交的明细与启用规则，生成结果并写回分析列。
 * <p>任何异常都向上抛出，由调用方在事务外决定是否标记 FAILED；
 * 独立成 bean 是为了避免「回滚标记」污染保存事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealAnalysisTxWriter {

    private static final int ENABLED = 1;
    private static final int STATUS_READY = 1;

    private final FoodSuitabilityRuleMapper ruleMapper;
    private final MealRecordMapper mealRecordMapper;
    private final MealFoodItemMapper mealFoodItemMapper;

    /** 引擎无状态、不依赖容器。 */
    private final SuitabilityRuleEngine engine = new SuitabilityRuleEngine();

    /**
     * @return 写入成功的分析快照（含规则版本）；
     * @throws IllegalStateException 暂无启用规则时抛出，由调用方标记 FAILED
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MealVos.Analysis write(Long userId, Long mealId) {
        List<MealFoodItem> rows = mealFoodItemMapper.selectList(
                new LambdaQueryWrapper<MealFoodItem>()
                        .eq(MealFoodItem::getMealId, mealId)
                        .orderByAsc(MealFoodItem::getSortNo)
                        .orderByAsc(MealFoodItem::getId));
        List<FoodSuitabilityRule> enabledRules = ruleMapper.selectList(
                Wrappers.<FoodSuitabilityRule>lambdaQuery().eq(FoodSuitabilityRule::getIsEnabled, ENABLED));

        String ruleVersion = enabledRules.stream()
                .map(FoodSuitabilityRule::getRuleVersion)
                .filter(version -> version != null && !version.isBlank())
                .max(String::compareTo)
                .orElseThrow(() -> new IllegalStateException("暂无启用的适食规则"));
        List<FoodSuitabilityRule> rules = enabledRules.stream()
                .filter(rule -> ruleVersion.equals(rule.getRuleVersion()))
                .toList();

        MealSuitabilityDtos.Evaluation evaluation =
                engine.evaluate(rules, rows.stream().map(MealAnalysisTxWriter::toInput).toList());

        List<MealVos.AnalysisItem> analysisItems = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            MealFoodItem row = rows.get(i);
            MealSuitabilityDtos.ItemResult result = evaluation.getItems().get(i);
            row.setAdviceTier(result.getTier());
            row.setAdviceReasonCode(result.getRuleCode());
            row.setAdviceText(MealAnalysisTexts.adviceOf(result.getTier()));
            mealFoodItemMapper.updateById(row);
            analysisItems.add(MealVos.AnalysisItem.builder()
                    .foodName(row.getFoodName())
                    .tier(result.getTier())
                    .ruleCode(result.getRuleCode())
                    .advice(MealAnalysisTexts.adviceOf(result.getTier()))
                    .build());
        }

        mealRecordMapper.update(null,
                new LambdaUpdateWrapper<MealRecord>()
                        .eq(MealRecord::getId, mealId)
                        .eq(MealRecord::getUserId, userId)
                        .set(MealRecord::getAnalysisStatus, STATUS_READY)
                        .set(MealRecord::getAnalysisTier, evaluation.getOverallTier())
                        .set(MealRecord::getAnalysisSummary,
                                MealAnalysisTexts.summaryOf(evaluation.getOverallTier()))
                        .set(MealRecord::getAnalysisRuleVersion, ruleVersion)
                        .set(MealRecord::getAnalysisGeneratedAt, LocalDateTime.now()));

        return MealVos.Analysis.builder()
                .tier(evaluation.getOverallTier())
                .tierLabel(MealAnalysisTexts.labelOf(evaluation.getOverallTier()))
                .summary(MealAnalysisTexts.summaryOf(evaluation.getOverallTier()))
                .ruleVersion(ruleVersion)
                .items(analysisItems)
                .build();
    }

    private static MealSuitabilityDtos.ItemInput toInput(MealFoodItem row) {
        MealSuitabilityDtos.ItemInput input = new MealSuitabilityDtos.ItemInput();
        input.setFoodId(row.getFoodId());
        input.setFoodName(row.getFoodName());
        input.setCategoryCode(row.getCategoryCode());
        input.setGiValue(row.getGiValue());
        input.setConfidence(row.getConfidence());
        return input;
    }
}
