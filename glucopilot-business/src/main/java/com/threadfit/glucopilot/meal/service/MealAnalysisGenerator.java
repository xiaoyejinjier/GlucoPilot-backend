package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.meal.vo.MealVos;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保存后的餐食分析生成器。
 * <p>分析在保存事务提交后独立执行：成功回填分析列并返回 READY，失败只把 analysis_status 置 FAILED，
 * 不影响主记录与明细。
 */
public interface MealAnalysisGenerator {

    String STATUS_PENDING = "PENDING";
    String STATUS_READY = "READY";
    String STATUS_FAILED = "FAILED";

    /**
     * @param previousRuleVersion 本次保存前该餐使用的规则版本，用于计算 ruleVersionChanged；无历史分析时为 null
     */
    Result generate(Long userId, Long mealId, String previousRuleVersion);

    /** 分析结果：status 必填，失败时 analysis 为 null。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class Result {
        private String status;
        private MealVos.Analysis analysis;
        private boolean ruleVersionChanged;
    }
}
