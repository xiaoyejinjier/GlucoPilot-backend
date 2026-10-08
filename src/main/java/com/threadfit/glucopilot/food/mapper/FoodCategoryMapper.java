package com.threadfit.glucopilot.food.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食品分类数据访问接口。
 *
 * <p>继承 {@link BaseMapper} 后，MyBatis-Plus 会在运行时为该接口创建代理对象，
 * 并提供 selectById、selectList、insert、updateById、selectCount 等通用 SQL。
 * 简单 CRUD 不需要手写 Mapper 实现类或 XML。</p>
 */
@Mapper
public interface FoodCategoryMapper extends BaseMapper<FoodCategory> {
    // 当前没有模块专用 SQL；以后出现复杂分类统计时，可在这里增加自定义方法。
}
