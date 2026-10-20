package com.threadfit.glucopilot.food.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 食品营养数据库实体，对应 {@code food_nutrition} 表。
 *
 * <p>每个对象表示食品营养库中的一条记录。继承 {@link BaseEntity} 后自动拥有
 * 主键、审计字段和逻辑删除字段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("food_nutrition")
public class FoodNutrition extends BaseEntity {

    /** 食品名称，对应 food_name。 */
    private String foodName;

    /** 所属分类主键，对应 category_id。 */
    private Long categoryId;

    /** 兼容旧业务的 1~9 大类编码，对应 category_code。 */
    private Integer categoryCode;

    /**
     * 每 100 克碳水。
     * MyBatis-Plus 默认驼峰可能映射成 carbs_per100g，与 DDL 的
     * carbs_per_100g 不一致，因此使用 @TableField 显式指定列名。
     */
    @TableField("carbs_per_100g")
    private BigDecimal carbsPer100g;

    /** 每 100 克热量；同样需要显式映射下划线前的数字部分。 */
    @TableField("calories_per_100g")
    private BigDecimal caloriesPer100g;

    /** GI 值，对应 gi_value，允许为空。 */
    private BigDecimal giValue;

    /** 食品图片 URL，对应 image_url。 */
    private String imageUrl;

    /** 上下架状态：0=下架，1=上架。 */
    private Integer shelfStatus;

    private String nutritionSource;
    private String sourceModel;

    /** 已校验归属的餐食照片，仅用于管理端审核，不能作为公开配图。 */
    private Long sourceMediaId;
}
