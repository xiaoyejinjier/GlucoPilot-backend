package com.threadfit.glucopilot.food.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 小程序端食品营养库的数据传输对象集合。
 *
 * <p>这里规定后端返回给小程序的数据结构。DTO 只保留页面展示需要的字段，
 * 不直接暴露 Entity 中的创建人、修改人和逻辑删除等数据库字段。</p>
 */
public final class FoodDtos {

    /** 工具容器类不需要创建实例，因此使用私有构造方法。 */
    private FoodDtos() {
    }

    /** 小程序分类列表中的单个分类。 */
    @Data
    @Builder
    public static class FoodCategoryItem {
        /** 分类主键；使用字符串避免小程序 JavaScript 大整数精度问题。 */
        private String id;

        /** 分类名称，例如“主食类”。 */
        private String categoryName;

        /** 与管理端共用 food_category.image_url；未设置时为空字符串。 */
        private String imageUrl;

        /** 分类展示顺序，数值越小越靠前。 */
        private Integer sortNo;
    }

    /** 小程序食品列表和食品详情共用的展示对象。 */
    @Data
    @Builder
    public static class FoodItem {
        /** 食品主键；使用字符串返回给小程序。 */
        private String id;

        /** 食品名称，例如“米饭”。 */
        private String foodName;

        /** 兼容旧业务使用的 1~9 大类编码。 */
        private Integer categoryCode;

        /** 食品分类表主键；使用字符串返回给小程序。 */
        private String categoryId;

        /** 通过 categoryId 从分类表补充的分类名称。 */
        private String categoryName;

        /** 每 100 克食品包含的碳水化合物，单位为克。 */
        private BigDecimal carbsPer100g;

        /** 每 100 克食品包含的热量，单位为千卡。 */
        private BigDecimal caloriesPer100g;

        /** 食品 GI 值；数据库允许为空。 */
        private BigDecimal giValue;

        /** 食品缩略图 URL；当前不包含文件上传逻辑。 */
        private String imageUrl;
    }
}
