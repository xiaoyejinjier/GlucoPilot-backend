package com.threadfit.glucopilot.ai.business.meal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

public final class MealAnalysisDtos {
    private MealAnalysisDtos() { }
    @Data
    public static class Request {
        @NotNull @Positive private Long mediaId;
        @NotBlank private String nodeCode;
        @Size(max = 200) private String foodText;
    }
    @Data
    public static class ModelResult {
        private Integer schemaVersion;
        private List<ModelFood> foods;
        private String warning;
    }
    @Data
    public static class ModelFood {
        private String name;
        private Integer categoryCode;
        private BigDecimal weightG;
        private BigDecimal carbsPer100g;
        private BigDecimal caloriesPer100g;
        private Integer confidence;
    }
    @Data @Builder @NoArgsConstructor
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.DISABLED))
    public static class Result {
        private String model;
        /** 识别快照令牌：保存时回传用于关联「哪次识别」；快照写失败或旧缓存时为 null。 */
        private String analysisToken;
        private List<Item> items;
        private BigDecimal carbsG;
        private BigDecimal caloriesKcal;
        private Integer matchedCount;
        private Integer newFoodsCount;
        private List<String> warnings;
        /**
         * 整餐是否需要用户确认：任一明细需确认即为 true；明细为空时也恒为 true（空结果最需要用户介入）。
         * <p>
         * 用原始 boolean + {@code @JsonSetter(nulls = SKIP)}：Jackson 3 默认开启
         * {@code FAIL_ON_NULL_FOR_PRIMITIVES}，旧缓存里显式写 null 会抛 MismatchedInputException，
         * SKIP 让 null 退回默认值而不是报错；缺失字段则由下面禁用全参 creator 兜住。
         */
        @Builder.Default
        @JsonSetter(nulls = Nulls.SKIP)
        private boolean needsConfirmation = false;
    }
    @Data @Builder @NoArgsConstructor
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.DISABLED))
    public static class Item {
        private String foodName;
        private String foodId;
        private String libraryFoodId;
        private Integer categoryCode;
        private BigDecimal weightG;
        private BigDecimal carbsPer100g;
        private BigDecimal caloriesPer100g;
        private BigDecimal carbsG;
        private BigDecimal caloriesKcal;
        private Integer confidence;
        private BigDecimal giValue;
        private String nutritionSource;
        private String libraryStatus;
        /**
         * 单条明细是否需要用户确认：置信度低于阈值，或未命中营养库（UNRESOLVED），或命中但待审（PENDING_REVIEW）。
         * <p>同 Result：原始 boolean + null 跳过，兼容不含该字段/显式 null 的旧缓存。
         */
        @Builder.Default
        @JsonSetter(nulls = Nulls.SKIP)
        private boolean needsConfirmation = false;
    }
}
