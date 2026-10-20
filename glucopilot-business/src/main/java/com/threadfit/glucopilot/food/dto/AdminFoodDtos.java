package com.threadfit.glucopilot.food.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端食品营养库的数据传输对象集合。
 *
 * <p>包含管理页面展示使用的返回对象，以及新增、修改分类和食品时接收的
 * 请求对象。使用 DTO 可以把管理端字段命名与数据库 Entity 解耦。</p>
 */
public final class AdminFoodDtos {

    /** 工具容器类不需要实例化。 */
    private AdminFoodDtos() {
    }

    /** 管理端分类列表中的单个分类。 */
    @Data
    @Builder
    public static class FoodCategoryItem {
        /** 分类主键。 */
        private Long id;

        /** 管理端展示的分类名称。 */
        private String name;

        /** 分类图片 URL，可为空。 */
        private String image;

        /** 分类排序值。 */
        private Integer sort;
    }

    /** 管理端新增或修改分类时提交的请求体。 */
    @Data
    public static class FoodCategorySaveRequest {
        /** 修改时由 Controller 设置；新增时为空。 */
        private Long id;

        /** 分类名称不能为空或全是空格。 */
        @NotBlank
        private String name;

        /** 省略时保留已有图片，空字符串表示使用默认图片。 */
        @Size(max = 512)
        private String image;

        /** 分类排序；未填写时 Service 使用 0。 */
        private Integer sort;
    }

    /** 管理端食品列表中的单个食品。 */
    @Data
    @Builder
    public static class FoodItem {
        /** 食品主键。 */
        private Long id;

        /** 食品名称。 */
        private String name;

        /** 所属分类主键。 */
        private Long categoryId;

        /** 所属分类名称，由 Service 查询分类表后补充。 */
        private String categoryName;

        /** 每 100 克碳水，单位为克。 */
        private BigDecimal carbs;

        /** 每 100 克热量，单位为千卡。 */
        private BigDecimal cal;

        /** GI 值。 */
        private BigDecimal gi;

        /** 食品图片 URL。 */
        private String image;

        /** 食品描述；当前数据库尚未落库，返回值暂为空字符串。 */
        private String description;

        /** true 表示上架，false 表示下架。 */
        private Boolean enabled;

        /** 格式化后的最后修改时间。 */
        private String updatedAt;
        private String nutritionSource;
        private String sourceModel;
        /** 待审核实拍图的鉴权读取路径，只读，不能保存为公开配图。 */
        private String reviewImage;
    }

    /** 管理端新增或修改食品时提交的请求体。 */
    @Data
    public static class FoodSaveRequest {
        /** 修改时由 Controller 设置；新增时为空。 */
        private Long id;

        /** 食品名称不能为空。 */
        @NotBlank
        private String name;

        /** 食品必须属于一个分类。 */
        @NotNull
        private Long categoryId;

        /** 每 100 克碳水；未填写时 Service 保存为 0。 */
        private BigDecimal carbs;

        /** 每 100 克热量；未填写时 Service 保存为 0。 */
        private BigDecimal cal;

        /** GI 值，可不填写。 */
        private BigDecimal gi;

        /** 食品图片 URL。 */
        private String image;

        /** 食品描述；当前请求字段已预留，但 Service 尚未持久化。 */
        private String description;

        /** 是否上架；明确为 false 时下架，其余情况默认上架。 */
        private Boolean enabled;
    }
}
