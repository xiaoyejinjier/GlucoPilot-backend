package com.threadfit.glucopilot.food.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** B 端营养库 DTO（字段对齐 admin 前端展示模型）。 */
public final class AdminFoodDtos {

    private AdminFoodDtos() {
    }

    @Data
    @Builder
    public static class FoodCategoryItem {
        private Long id;
        private String name;
        private Integer sort;
    }

    @Data
    public static class FoodCategorySaveRequest {
        private Long id;
        @NotBlank
        private String name;
        private Integer sort;
    }

    @Data
    @Builder
    public static class FoodItem {
        private Long id;
        private String name;
        private Long categoryId;
        private String categoryName;
        private BigDecimal carbs;
        private BigDecimal cal;
        private BigDecimal gi;
        private String image;
        private String description;
        private Boolean enabled;
        private String updatedAt;
    }

    @Data
    public static class FoodSaveRequest {
        private Long id;
        @NotBlank
        private String name;
        @NotNull
        private Long categoryId;
        private BigDecimal carbs;
        private BigDecimal cal;
        private BigDecimal gi;
        private String image;
        private String description;
        private Boolean enabled;
    }
}
