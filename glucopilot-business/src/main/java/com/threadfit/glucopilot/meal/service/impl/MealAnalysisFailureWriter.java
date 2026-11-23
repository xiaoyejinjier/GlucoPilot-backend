package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 分析失败标记：独立事务写 analysis_status=2，保证分析异常不拖垮保存结果。 */
@Service
@RequiredArgsConstructor
public class MealAnalysisFailureWriter {

    private static final int STATUS_FAILED = 2;

    private final MealRecordMapper mealRecordMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long userId, Long mealId) {
        mealRecordMapper.update(null,
                new LambdaUpdateWrapper<MealRecord>()
                        .eq(MealRecord::getId, mealId)
                        .eq(MealRecord::getUserId, userId)
                        .set(MealRecord::getAnalysisStatus, STATUS_FAILED));
    }
}
