package com.threadfit.glucopilot.prediction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * C 端餐后血糖预测 DTO。DTO 层只挡明显非法值（校验注解），「餐点节点校验、未建档兜底」等
 * 业务规则在 Service 层处理；DTO 不承载任何持久化或落库逻辑。
 */
public final class PredictionDtos {

    private PredictionDtos() {
    }

    /**
     * 预测入参：当前血糖客户端传入（G2），碳水与明细来自 ai 分析结果转发（F1）。
     * items 的 categoryCode（1-9）透传不落库，仅用于分档建议。
     */
    @Data
    public static class PredictRequest {
        @NotBlank
        private String nodeCode;

        /** 当前血糖 (0,40)，对齐 glucose 录入口径。 */
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal currentGlucose;

        /** 碳水汇总（g，必填）。 */
        @NotNull
        @DecimalMin(value = "0")
        private BigDecimal carbsG;

        @Valid
        @Size(max = 50)
        private List<Item> items;

        /** 份量比例 (0,2]，默认按标准份 1.0 处理。 */
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "2")
        private BigDecimal portionRatio;

        private Boolean medicationTaken;

        private Boolean exerciseDone;
    }

    @Data
    public static class Item {
        @NotBlank
        @Size(max = 64)
        private String foodName;

        /** 分类码 1-9（透传不落库），来源非用户手选：前端从 food 库选食详情带 category_code。 */
        @NotNull
        @Min(1)
        @Max(9)
        private Integer categoryCode;

        @DecimalMin(value = "0")
        private BigDecimal weightG;

        @DecimalMin(value = "0")
        private BigDecimal carbsG;
    }

    @Data
    @Builder
    public static class PredictResult {
        /** 现状预测（当前摄入下的预测血糖）。 */
        private BigDecimal predictNow;
        /** 建议文案：分食物三档削减建议或整体降级提示。 */
        private String suggestText;
        /** 按建议碳水摄入的预测血糖。 */
        private BigDecimal predictSuggest;
        /** 算法版本号（当前 v1 参数）。 */
        private String algorithmVersion;
    }
}