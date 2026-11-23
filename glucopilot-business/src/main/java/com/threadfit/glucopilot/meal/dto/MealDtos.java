package com.threadfit.glucopilot.meal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * 阶段 2b-2 起 Item 携带营养快照字段（categoryCode/giValue/nutritionSource/confidence 等）；
 * 预测字段保留一个版本以兼容旧客户端，服务端不再写入。
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
        /** 已下线：兼容旧客户端解析但服务端忽略，不落库；过渡期后删除 */
        @Deprecated
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal predictNow;
        /** 已下线：兼容旧客户端解析但服务端忽略，不落库；过渡期后删除 */
        @Deprecated
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal predictSuggest;
        /** 已下线：兼容旧客户端解析但服务端忽略，不落库；过渡期后删除 */
        @Deprecated
        @Size(max = 512)
        private String suggestText;
        /** 已下线：兼容旧客户端解析但服务端忽略，不落库；过渡期后删除 */
        @Deprecated
        @Size(max = 8)
        private String algorithmVersion;
        /** 已下线：兼容旧客户端解析但服务端忽略，不落库；过渡期后删除 */
        @Deprecated
        @Size(max = 16)
        private String recommendationStatus;
        /** 客户端版本（如 2.0.0）；缺省视为旧客户端，走 legacy 分支不校验低置信确认 */
        @Size(max = 16)
        private String clientVersion;
        /** 期望的规则版本（如 2026.11.1）；可空，缺省取表内最新启用版本 */
        @Size(max = 32)
        private String ruleVersion;
        /** 用户是否已确认低置信项；新客户端低置信未确认 → 40001，旧客户端为 null 时跳过拦截 */
        private Boolean confirmLowConfidence;
        /** 识别快照令牌；字段已预留，3b2 起服务端才消费（3b1 只解析不使用） */
        @Size(max = 64)
        private String analysisToken;
        /** 食物明细；可空 */
        @Size(max = 50)
        private List<@Valid Item> items;
    }

    /** 单行食物明细：营养值按客户端快照落库，2b-3 起由服务端按 libraryFoodId/foodId 归一化后落库。 */
    @Data
    public static class Item {
        /** 营养库食物 ID；可空 = 手输食物（此时不参与 food 存在性校验） */
        private Long foodId;
        /** 营养库待审行 ID（识别草稿）；与 foodId 二选一，服务端优先按它回读 */
        private Long libraryFoodId;
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
        /** 食物大类 1-9；可空，缺省由服务端按营养库回填 */
        @Min(1)
        @Max(9)
        private Integer categoryCode;
        /** GI（升糖指数）；可空，缺省由服务端按营养库回填。DTO 不设业务上限，约束交给规则层 */
        @DecimalMin(value = "0")
        private BigDecimal giValue;
        /** 营养来源：LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN；可空，服务端归一化时写回 */
        @Size(max = 16)
        private String nutritionSource;
        /** 识别置信度 0-100；可空，手输项无置信度 */
        @Min(0)
        @Max(100)
        private Integer confidence;
        /** 是否人工修正；可空，默认 false */
        private Boolean userCorrected;
    }
}
