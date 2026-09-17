package com.threadfit.glucopilot.content.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("food_nutrition")
public class FoodNutrition extends BaseEntity {

    private String foodName;
    private Long categoryId;
    private Integer categoryCode;
    /** MP 驼峰会变成 carbs_per100g，与 DDL carbs_per_100g 不一致，须显式映射 */
    @TableField("carbs_per_100g")
    private BigDecimal carbsPer100g;
    @TableField("calories_per_100g")
    private BigDecimal caloriesPer100g;
    private BigDecimal giValue;
    private String imageUrl;
    private Integer shelfStatus;
}
