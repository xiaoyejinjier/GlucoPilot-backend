package com.threadfit.glucopilot.meal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface MealFoodItemMapper extends BaseMapper<MealFoodItem> {

    /**
     * 账号注销：先删明细再删主表。明细表只有 meal_id，没有 user_id，
     * 因此用子查询定位归属；必须在 meal_record 被删之前执行。
     */
    @Delete("DELETE FROM meal_food_item WHERE meal_id IN (SELECT id FROM meal_record WHERE user_id = #{userId})")
    int purgeByUserId(@Param("userId") Long userId);
}
