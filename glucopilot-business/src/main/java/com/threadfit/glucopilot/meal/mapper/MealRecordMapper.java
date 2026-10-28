package com.threadfit.glucopilot.meal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface MealRecordMapper extends BaseMapper<MealRecord> {

    /** 账号注销：物理删除该用户全部饮食主记录（调用方须先删 meal_food_item）。 */
    @Delete("DELETE FROM meal_record WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
