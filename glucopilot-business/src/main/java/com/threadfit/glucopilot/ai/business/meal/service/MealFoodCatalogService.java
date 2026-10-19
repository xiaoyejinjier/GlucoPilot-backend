package com.threadfit.glucopilot.ai.business.meal.service;

import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.impl.MealAnalysisServiceImpl;

/**
 * 模型输出 → 食物库条目的归一化契约。
 *
 * <h2>为什么要这一步</h2>
 * 模型返回的是自由文本（"一碗牛肉面"），而营养计算与统计需要稳定条目与稳定热量口径。
 * 归一化做三件事：名称标准化（去空格/全半角/常见修饰）、命中已有条目、未命中则新建待审条目。
 *
 * <h2>为什么不直接相信模型给的营养数值</h2>
 * 同一道菜两次识别给出的热量可以差一倍；以食物库为准、模型只负责"认出是什么"，
 * 结果才可复现。模型给的数值仅在没有库条目时兜底。
 */
public interface MealFoodCatalogService {

    /** 归一化一条模型识别结果；added=true 表示为此新建了库条目。 */
    Resolution resolve(MealAnalysisDtos.ModelFood input, String model);

    /**
     * 名称标准化（NFKC 归一 + 空白折叠），供匹配与去重用。
     * <p>
     * 放在接口上是刻意的：{@code MealAnalysisServiceImpl} 也要用它来对齐"模型给的名字"与"库里的名字"，
     * 两处各写一份迟早会出现"同一条目被当成两条"。实现保持与原实现一致（NFKC + 折叠空白），不改行为。
     */
    static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    /** 归一化结果：命中的条目（可为新建的）+ 是否新增。 */
    record Resolution(MealAnalysisDtos.Item item, boolean added) {
    }
}