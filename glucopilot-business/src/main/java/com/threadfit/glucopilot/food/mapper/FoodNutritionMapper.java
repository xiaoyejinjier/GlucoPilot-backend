package com.threadfit.glucopilot.food.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

/**
 * 食品营养数据访问接口。
 *
 * <p>继承 {@link BaseMapper} 后自动获得通用增删改查与分页方法。Service 中的
 * LambdaQueryWrapper 会被 MyBatis-Plus 转换为带参数的 SQL 并发送给 MySQL。</p>
 */
@Mapper
public interface FoodNutritionMapper extends BaseMapper<FoodNutrition> {
    /** 唯一键冲突不覆盖已有营养；回读原记录处理并发识别。 */
    @Insert("""
        INSERT INTO food_nutrition
          (food_name, category_id, category_code, carbs_per_100g, calories_per_100g,
           shelf_status, nutrition_source, source_model, created_by, updated_by)
        VALUES (#{foodName}, #{categoryId}, #{categoryCode}, #{carbsPer100g}, #{caloriesPer100g},
                0, 'AI_ESTIMATE', #{sourceModel}, #{createdBy}, #{updatedBy})
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    int insertRecognitionDraft(FoodNutrition food);
}
