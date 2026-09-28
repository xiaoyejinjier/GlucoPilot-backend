package com.threadfit.glucopilot.food.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("food_category")
public class FoodCategory extends BaseEntity {

    private String categoryName;
    private Integer sortNo;
    private Integer isEnabled;
}
