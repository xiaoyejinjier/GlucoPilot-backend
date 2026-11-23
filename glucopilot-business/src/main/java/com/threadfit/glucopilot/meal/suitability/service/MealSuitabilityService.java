package com.threadfit.glucopilot.meal.suitability.service;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;

/**
 * 适食分析（C 端）：纯规则计算，不写库、不调用大模型、不读血糖。
 */
public interface MealSuitabilityService {

    /**
     * 餐食适食分析预览。
     *
     * @param userId  当前登录用户，本期不参与判定，仅固定服务契约
     * @param request 规则版本（可空，空取最新启用版本）+ 食物项
     */
    MealSuitabilityDtos.PreviewResponse preview(Long userId, MealSuitabilityDtos.PreviewRequest request);
}
