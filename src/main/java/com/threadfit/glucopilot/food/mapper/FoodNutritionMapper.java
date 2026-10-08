package com.threadfit.glucopilot.food.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食品营养数据访问接口。
 *
 * <p>继承 {@link BaseMapper} 后自动获得通用增删改查与分页方法。Service 中的
 * LambdaQueryWrapper 会被 MyBatis-Plus 转换为带参数的 SQL 并发送给 MySQL。</p>
 */
@Mapper
public interface FoodNutritionMapper extends BaseMapper<FoodNutrition> {
    // 当前查询均可由 BaseMapper 完成，因此接口体暂时不需要自定义 SQL 方法。
}
