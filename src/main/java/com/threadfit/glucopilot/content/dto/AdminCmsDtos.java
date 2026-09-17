package com.threadfit.glucopilot.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** B 端 CMS / 营养库 DTO（字段对齐 admin 前端展示模型） */
public final class AdminCmsDtos {

    private AdminCmsDtos() {
    }

    @Data
    @Builder
    public static class NewsItem {
        private Long id;
        private String title;
        private String summary;
        private String channel;
        private String type;
        private String coverUrl;
        private String coverText;
        private String videoUrl;
        private String content;
        private String status;
        private String publishAt;
        private Integer views;
        private Integer likes;
        private Integer collects;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    public static class NewsSaveRequest {
        private Long id;
        @NotBlank
        private String title;
        private String summary;
        @NotBlank
        private String channel;
        @NotBlank
        private String type;
        private String coverUrl;
        private String coverText;
        private String videoUrl;
        private String content;
        /** draft | published | unpublished */
        private String status;
        private String publishAt;
    }

    @Data
    @Builder
    public static class BannerItem {
        private Long id;
        private String title;
        private String image;
        private String linkType;
        private String linkValue;
        private Integer sort;
        private Boolean enabled;
        private String remark;
    }

    @Data
    public static class BannerSaveRequest {
        private Long id;
        @NotBlank
        private String title;
        private String image;
        private String linkType;
        private String linkValue;
        private Integer sort;
        private Boolean enabled;
        private String remark;
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

    @Data
    @Builder
    public static class ListWrap<T> {
        private List<T> records;
    }
}
