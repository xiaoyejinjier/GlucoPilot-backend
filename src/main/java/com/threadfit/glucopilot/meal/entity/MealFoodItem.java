package com.threadfit.glucopilot.meal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 饮食明细实体（meal_food_item，见 V1 L499-523）。
 * 隶属于主记录（mealId）；每次保存按旧删新建重建，sortNo 为顺序序号。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meal_food_item")
public class MealFoodItem extends BaseEntity {

    private Long mealId;
    /** 营养库食物 ID，可空 = 手输 */
    private Long foodId;
    /** 食物名称快照（必填） */
    private String foodName;
    /** 克重快照 */
    private BigDecimal weightG;
    /** 碳水克数快照 */
    private BigDecimal carbsG;
    /** 热量（kcal）快照 */
    private BigDecimal caloriesKcal;
    /** 明细顺序（0 起） */
    private Integer sortNo;
}
