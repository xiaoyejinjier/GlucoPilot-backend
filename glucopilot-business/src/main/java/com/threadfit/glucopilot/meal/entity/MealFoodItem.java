package com.threadfit.glucopilot.meal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 饮食明细（meal_food_item，见 V1 L499-523）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_food_item")
public class MealFoodItem extends BaseEntity {

    private Long mealId;
    private Long foodId;
    private String foodName;
    private BigDecimal weightG;
    private BigDecimal carbsG;
    private BigDecimal caloriesKcal;
    private Integer sortNo;
}
