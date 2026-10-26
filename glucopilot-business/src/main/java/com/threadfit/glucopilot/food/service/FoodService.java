package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 小程序端食品营养库业务层。
 *
 * <p>Service 是食品查询模块的业务核心，负责组合数据库查询条件、执行
 * “只展示启用分类和上架食品”等规则，并把数据库实体转换为小程序 DTO。
 * 它不负责接收 HTTP 参数，也不直接拼接 SQL。</p>
 */
@Service
// Lombok 根据两个 final Mapper 字段生成构造方法，供 Spring 依赖注入。
@RequiredArgsConstructor
public class FoodService {

    /** food_nutrition.shelf_status：1 表示食品已上架。 */
    private static final int SHELF_ON = 1;

    /** food_category.is_enabled：1 表示分类已启用。 */
    private static final int CATEGORY_ENABLED = 1;

    /** 负责访问 food_category 表。 */
    private final FoodCategoryMapper foodCategoryMapper;

    /** 负责访问 food_nutrition 表。 */
    private final FoodNutritionMapper foodNutritionMapper;

    /**
     * 查询小程序可见的食品分类。
     *
     * <p>只读取启用分类，先按 sort_no 排序，再按 id 保证相同排序值下结果稳定。</p>
     */
    public List<FoodDtos.FoodCategoryItem> listFoodCategories() {
        // BaseMapper.selectList 会由 MyBatis-Plus 根据 LambdaQueryWrapper 生成 SQL。
        return foodCategoryMapper.selectList(new LambdaQueryWrapper<FoodCategory>()
                        .eq(FoodCategory::getIsEnabled, CATEGORY_ENABLED)
                        .orderByAsc(FoodCategory::getSortNo)
                        .orderByAsc(FoodCategory::getId))
                .stream()
                // 数据库 Entity 不能直接暴露给前端，这里转换成用户端分类 DTO。
                .map(c -> FoodDtos.FoodCategoryItem.builder()
                        // Long 转 String，避免 JavaScript 大整数精度问题。
                        .id(String.valueOf(c.getId()))
                        .categoryName(c.getCategoryName())
                        .imageUrl(c.getImageUrl() == null ? "" : c.getImageUrl())
                        .sortNo(c.getSortNo())
                        .build())
                .toList();
    }

    /**
     * 分页检索上架食品。
     *
     * @param categoryId 分类表主键；不为空时增加等值条件
     * @param categoryCode 兼容大类编码；不为空时增加等值条件
     * @param keyword 食品名称关键字；有内容时执行模糊查询
     * @param query 已通过校验的分页参数
     * @return 包含页码、总数、总页数和食品 DTO 的统一分页结果
     */
    public PageResult<FoodDtos.FoodItem> pageFoods(Long categoryId, Integer categoryCode, String keyword, PageQuery query) {
        // 第一步：查询 food_nutrition 表，并让 MyBatis-Plus 同时执行分页统计。
        Page<FoodNutrition> page = foodNutritionMapper.selectPage(
                // 将前端页码和每页条数转换为 MyBatis-Plus Page 对象。
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<FoodNutrition>()
                        // 用户端永远只展示已上架食品。
                        .eq(FoodNutrition::getShelfStatus, SHELF_ON)
                        // LambdaQueryWrapper 的第一个参数为 false 时，不会拼接该条件。
                        .eq(categoryId != null, FoodNutrition::getCategoryId, categoryId)
                        .eq(categoryCode != null, FoodNutrition::getCategoryCode, categoryCode)
                        // keyword 有文本时，生成 food_name LIKE '%关键字%'。
                        .like(StringUtils.hasText(keyword), FoodNutrition::getFoodName,
                                keyword == null ? null : keyword.trim())
                        // 排序保证每次翻页时结果顺序稳定。
                        .orderByAsc(FoodNutrition::getFoodName)
                        .orderByAsc(FoodNutrition::getId));

        // 第二步：一次性查询“分类ID -> 分类名称”，用于给食品结果补充分类名称。
        Map<Long, String> categoryNames = categoryNameMap();

        // 第三步：保留原分页信息，同时把 FoodNutrition Entity 转成 FoodItem DTO。
        Page<FoodDtos.FoodItem> mapped = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        mapped.setRecords(page.getRecords().stream()
                .map(f -> toFoodItem(f, categoryNames.get(f.getCategoryId())))
                .toList());

        // 第四步：转换成项目统一的 PageResult，交给 Controller 包装为 ApiResponse。
        return PageResult.of(mapped);
    }

    /**
     * 根据主键查询单个食品详情。
     *
     * <p>即使数据库中存在记录，只要食品已下架，也不允许小程序端查看。</p>
     */
    public FoodDtos.FoodItem getFoodDetail(Long foodId) {
        // selectById 会自动带上 BaseEntity 中的 is_deleted=0 逻辑删除条件。
        FoodNutrition food = foodNutritionMapper.selectById(foodId);
        if (food == null || food.getShelfStatus() == null || food.getShelfStatus() != SHELF_ON) {
            throw new BizException(ErrorCode.NOT_FOUND, "食物不存在或已下架");
        }
        // 查询分类名称后，将数据库实体转换为前端详情 DTO。
        return toFoodItem(food, categoryNameMap().get(food.getCategoryId()));
    }

    /** 批量返回上架食物（供 meal 等消费方做存在性校验），已下架/不存在的 ID 不会出现在结果中。 */
    public List<FoodNutrition> listAvailableByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return foodNutritionMapper.selectList(new LambdaQueryWrapper<FoodNutrition>()
                .in(FoodNutrition::getId, ids)
                .eq(FoodNutrition::getShelfStatus, SHELF_ON));
    }

    /**
     * 构造分类 ID 到分类名称的映射。
     * 这样转换多条食品时不需要为每条食品分别查询一次分类表。
     */
    private Map<Long, String> categoryNameMap() {
        return foodCategoryMapper.selectList(null).stream()
                // 如果出现重复 ID，保留先读取到的名称；正常主键约束下不会重复。
                .collect(Collectors.toMap(FoodCategory::getId, FoodCategory::getCategoryName, (a, b) -> a));
    }

    /** 将数据库食品实体转换成小程序需要的展示 DTO。 */
    private static FoodDtos.FoodItem toFoodItem(FoodNutrition f, String categoryName) {
        return FoodDtos.FoodItem.builder()
                .id(String.valueOf(f.getId()))
                .foodName(f.getFoodName())
                .categoryCode(f.getCategoryCode())
                .categoryId(f.getCategoryId() == null ? null : String.valueOf(f.getCategoryId()))
                .categoryName(categoryName == null ? "" : categoryName)
                .carbsPer100g(f.getCarbsPer100g())
                .caloriesPer100g(f.getCaloriesPer100g())
                .giValue(f.getGiValue())
                .imageUrl(f.getImageUrl())
                .build();
    }
}
