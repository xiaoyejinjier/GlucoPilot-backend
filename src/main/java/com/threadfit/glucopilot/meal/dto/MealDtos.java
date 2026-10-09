package com.threadfit.glucopilot.meal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * C 端饮食记录 DTO。
 * DTO 层只做标注/长度等显式校验，拦截明显非法值；「餐点节点校验」「照片归属」「食物存在性」等业务规则交给 Service。
 * 注意：meal 明细（Item）没有 categoryCode 字段，该字段仅用于 prediction 入参，此处以实际代码为准。
 */
public final class MealDtos {

    private MealDtos() {
    }

    /**
     * 覆盖写入：recordDate + nodeCode 标识「哪天哪餐」，同值重复提交即覆盖当天该餐记录；
     * 预测三列由 prediction 客户端串联携带、可空（两模块未上线期间存 null）。
     */
    @Data
    public static class SaveRequest {
        /** 记录日期（幂等键的组成部分） */
        @NotNull
        private LocalDate recordDate;
        /** 餐点节点编码，须为三餐之一（is_meal=1），Service 校验 */
        @NotBlank
        private String nodeCode;
        /** 餐食照片 ID；可空，归属/类型在 Service 经 file 侧校验 */
        private Long mediaId;
        /** 食物简述；可空，缺省时由 Service 用明细 foodName 拼接兜底 */
        @Size(max = 512)
        private String foodsText;
        /** 碳水克数；可空，缺省时由 Service 用明细 carbsG 求和兜底 */
        @DecimalMin(value = "0")
        private BigDecimal carbsG;
        /** 饥饿感：1饱腹 2一般 3微饿；可空，非法值在 Service 校验 */
        private Integer hungerLevel;
        /** 补餐备注（如加餐说明）；可空 */
        @Size(max = 512)
        private String supplementText;
        /** 预测当前血糖（mmol/L），范围 (0,40)；可空，prediction 客户端串联携带 */
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal predictNow;
        /** 预测建议血糖，范围 (0,40)；可空 */
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal predictSuggest;
        /** 预测建议文案；可空 */
        @Size(max = 512)
        private String suggestText;
        /** 食物明细；可空。注意：明细项没有 categoryCode（仅 prediction 入参使用） */
        @Valid
        @Size(max = 50)
        private List<Item> items;
    }

    /** 单行食物明细：营养值按客户端快照落库，服务端不反向重算。 */
    @Data
    public static class Item {
        /** 营养库食物 ID；可空 = 手输食物（此时不参与 food 存在性校验） */
        private Long foodId;
        /** 食物名称，必填 */
        @NotBlank
        @Size(max = 64)
        private String foodName;
        /** 克重；可空 */
        @DecimalMin(value = "0")
        private BigDecimal weightG;
        /** 碳水克数；可空 */
        @DecimalMin(value = "0")
        private BigDecimal carbsG;
        /** 热量（kcal）；可空 */
        @DecimalMin(value = "0")
        private BigDecimal caloriesKcal;
    }
}
