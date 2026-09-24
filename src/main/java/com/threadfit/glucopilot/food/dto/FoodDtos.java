package com.threadfit.glucopilot.food.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** C 端营养库 DTO（字段与小程序展示模型对齐）。 */
public final class FoodDtos {

    private FoodDtos() {
    }

    @Data
    @Builder
    public static class FoodCategoryItem {
        private String id;
        private String categoryName;
        private Integer sortNo;
    }

    @Data
    @Builder
    public static class FoodItem {
        private String id;
        private String foodName;
        private Integer categoryCode;
        private String categoryId;
        private String categoryName;
        private BigDecimal carbsPer100g;
        private BigDecimal caloriesPer100g;
        private BigDecimal giValue;
        private String imageUrl;
    }
}
