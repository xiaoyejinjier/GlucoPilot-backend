package com.threadfit.glucopilot.ai.business.meal;

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
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Result {
        private String model;
        private List<Item> items;
        private BigDecimal carbsG;
        private BigDecimal caloriesKcal;
        private Integer matchedCount;
        private Integer newFoodsCount;
        private List<String> warnings;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
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
    }
}
