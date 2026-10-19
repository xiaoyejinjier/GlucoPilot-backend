package com.threadfit.glucopilot.ai.business.meal.service;

import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;

/**
 * 饮食分析 / 拍照估糖契约（接口 2.20）。
 *
 * <h2>它做的是"估算"，不是"医嘱"</h2>
 * 产物是营养构成与血糖影响的估算 + 建议，措辞与安全约束由提示词负责；
 * 剂量、用药调整一律在护栏层拦掉（见 MedicalGuardAdvisor）。
 *
 * <h2>为什么识别结果要经过食物库归一化</h2>
 * 模型给的是自由文本（"一碗牛肉面"），而统计与营养计算需要稳定条目。
 * 归一化在 {@link MealFoodCatalogService} 里做，本接口只负责编排。
 */
public interface MealAnalysisService {

    /** 2.20 分析一顿饭（文本描述或图片）。 */
    MealAnalysisDtos.Result analyze(Long userId, MealAnalysisDtos.Request request);
}