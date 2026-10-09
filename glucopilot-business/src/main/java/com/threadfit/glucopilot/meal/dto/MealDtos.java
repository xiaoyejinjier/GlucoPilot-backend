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
 * C 端饮食记录 DTO。DTO 层只挡明显非法值，「餐点节点校验」「照片归属」「食物存在性」等业务规则在 Service 校验。
 */
public final class MealDtos {

    private MealDtos() {
    }

    /**
     * 按 (recordDate, nodeCode) 覆盖写入（同日同餐点）。
     * 预测三列由 prediction 客户端串联携带，可空（两模块未上线期间存 null）。
     */
    @Data
    public static class SaveRequest {
        @NotNull
        private LocalDate recordDate;
        @NotBlank
        private String nodeCode;
        /** 餐食照片 ID；归属与类型在 Service 经 file 侧校验 */
        private Long mediaId;
        @Size(max = 512)
        private String foodsText;
        @DecimalMin(value = "0")
        private BigDecimal carbsG;
        /** 1饱腹 2一般 3微饿；可空，非法值在 Service 校验 */
        private Integer hungerLevel;
        @Size(max = 512)
        private String supplementText;
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal predictNow;
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "40", inclusive = false)
        private BigDecimal predictSuggest;
        @Size(max = 512)
        private String suggestText;
        @Valid
        @Size(max = 50)
        private List<Item> items;
    }

    @Data
    public static class Item {
        /** 营养库 ID，可空（手输食物） */
        private Long foodId;
        @NotBlank
        @Size(max = 64)
        private String foodName;
        @DecimalMin(value = "0")
        private BigDecimal weightG;
        @DecimalMin(value = "0")
        private BigDecimal carbsG;
        @DecimalMin(value = "0")
        private BigDecimal caloriesKcal;
    }
}
