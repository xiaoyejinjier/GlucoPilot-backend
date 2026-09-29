package com.threadfit.glucopilot.meal.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** C 端饮食记录 VO。 */
public final class MealVos {

    private MealVos() {
    }

    @Data
    @Builder
    public static class SaveResult {
        private String id;
        private Boolean created;
    }

    @Data
    @Builder
    public static class DayMeals {
        private LocalDate recordDate;
        private List<MealDetail> meals;
    }

    @Data
    @Builder
    public static class MealDetail {
        private String id;
        private String nodeCode;
        private String mediaId;
        private String mediaUrl;
        private String foodsText;
        private BigDecimal carbsG;
        private Integer hungerLevel;
        private String supplementText;
        private BigDecimal predictNow;
        private BigDecimal predictSuggest;
        private String suggestText;
        private LocalDateTime recordedAt;
        private List<MealItemDetail> items;
    }

    @Data
    @Builder
    public static class MealItemDetail {
        private String foodId;
        private String foodName;
        private BigDecimal weightG;
        private BigDecimal carbsG;
        private BigDecimal caloriesKcal;
    }
}
