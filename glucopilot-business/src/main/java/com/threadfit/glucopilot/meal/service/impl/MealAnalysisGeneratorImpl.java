package com.threadfit.glucopilot.meal.service.impl;

import com.threadfit.glucopilot.meal.service.MealAnalysisGenerator;
import com.threadfit.glucopilot.meal.vo.MealVos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 分析编排：在保存事务之外调用独立事务写入器，失败只标记 FAILED。
 * <p>本身不加事务注解，避免把分析异常的回滚标记带进保存事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealAnalysisGeneratorImpl implements MealAnalysisGenerator {

    private final MealAnalysisTxWriter analysisTxWriter;
    private final MealAnalysisFailureWriter analysisFailureWriter;

    @Override
    public Result generate(Long userId, Long mealId, String previousRuleVersion) {
        try {
            MealVos.Analysis analysis = analysisTxWriter.write(userId, mealId);
            boolean changed = StringUtils.hasText(previousRuleVersion)
                    && !previousRuleVersion.equals(analysis.getRuleVersion());
            return new Result(STATUS_READY, analysis, changed);
        } catch (Exception e) {
            log.warn("餐食分析生成失败，标记 FAILED：userId={} mealId={} error={}",
                    userId, mealId, e.toString());
            try {
                analysisFailureWriter.markFailed(userId, mealId);
            } catch (Exception markError) {
                // 标记失败也不能影响保存结果，仅留痕
                log.error("餐食分析 FAILED 标记写入异常：userId={} mealId={} error={}",
                        userId, mealId, markError.toString());
            }
            return new Result(STATUS_FAILED, null, false);
        }
    }
}
