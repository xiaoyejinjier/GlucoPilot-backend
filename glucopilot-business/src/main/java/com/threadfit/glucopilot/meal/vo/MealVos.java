package com.threadfit.glucopilot.meal.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** C 端饮食记录 VO 集合。 */
public final class MealVos {

    private MealVos() {
    }

    /** 保存结果：返回主记录 id 与本次是否新建（created=false 表示覆盖了既有记录）。 */
    @Data
    @Builder
    public static class SaveResult {
        private String id;
        private Boolean created;
    }

    /** 按日查询结果：一个日期 + 该日全部餐点详情（按餐次排序）。 */
    @Data
    @Builder
    public static class DayMeals {
        private LocalDate recordDate;
        private List<MealDetail> meals;
    }

    /** 单餐点详情：主记录字段 + 明细列表 + 照片可访问 URL。 */
    @Data
    @Builder
    public static class MealDetail {
        private String id;
        /** 餐点节点编码（如早餐/午餐/晚餐） */
        private String nodeCode;
        /** 照片 ID（字符串化），无则 null */
        private String mediaId;
        /** 照片可访问 URL（经当前用户鉴权），无则 null */
        private String mediaUrl;
        private String foodsText;
        private BigDecimal carbsG;
        /** 饥饿感：1饱腹 2一般 3微饿 */
        private Integer hungerLevel;
        private String supplementText;
        /** 预测当前/建议血糖（mmol/L），可为 null */
        private BigDecimal predictNow;
        private BigDecimal predictSuggest;
        private String suggestText;
        /** 记录保存时刻（服务端写入） */
        private LocalDateTime recordedAt;
        private List<MealItemDetail> items;
    }

    /** 单行食物明细 VO。 */
    @Data
    @Builder
    public static class MealItemDetail {
        /** 营养库食物 ID（手输时为 null），字符串化 */
        private String foodId;
        private String foodName;
        private BigDecimal weightG;
        private BigDecimal carbsG;
        private BigDecimal caloriesKcal;
    }
}
