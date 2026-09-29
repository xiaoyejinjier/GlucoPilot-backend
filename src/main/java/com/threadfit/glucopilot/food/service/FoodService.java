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
 * C 端营养库：分类全量、食物分页检索、食物营养详情。
 * 只暴露上架（shelf_status=1）的食物与启用（is_enabled=1）的分类。
 */
@Service
@RequiredArgsConstructor
public class FoodService {

    private static final int SHELF_ON = 1;
    private static final int CATEGORY_ENABLED = 1;

    private final FoodCategoryMapper foodCategoryMapper;
    private final FoodNutritionMapper foodNutritionMapper;

    public List<FoodDtos.FoodCategoryItem> listFoodCategories() {
        return foodCategoryMapper.selectList(new LambdaQueryWrapper<FoodCategory>()
                        .eq(FoodCategory::getIsEnabled, CATEGORY_ENABLED)
                        .orderByAsc(FoodCategory::getSortNo)
                        .orderByAsc(FoodCategory::getId))
                .stream()
                .map(c -> FoodDtos.FoodCategoryItem.builder()
                        .id(String.valueOf(c.getId()))
                        .categoryName(c.getCategoryName())
                        .sortNo(c.getSortNo())
                        .build())
                .toList();
    }

    /**
     * 分页检索上架食物。categoryId 按分类主键过滤，categoryCode 按大类枚举过滤，两者可同时传（AND）。
     */
    public PageResult<FoodDtos.FoodItem> pageFoods(Long categoryId, Integer categoryCode, String keyword, PageQuery query) {
        Page<FoodNutrition> page = foodNutritionMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<FoodNutrition>()
                        .eq(FoodNutrition::getShelfStatus, SHELF_ON)
                        .eq(categoryId != null, FoodNutrition::getCategoryId, categoryId)
                        .eq(categoryCode != null, FoodNutrition::getCategoryCode, categoryCode)
                        .like(StringUtils.hasText(keyword), FoodNutrition::getFoodName,
                                keyword == null ? null : keyword.trim())
                        .orderByAsc(FoodNutrition::getFoodName)
                        .orderByAsc(FoodNutrition::getId));

        Map<Long, String> categoryNames = categoryNameMap();
        Page<FoodDtos.FoodItem> mapped = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        mapped.setRecords(page.getRecords().stream()
                .map(f -> toFoodItem(f, categoryNames.get(f.getCategoryId())))
                .toList());
        return PageResult.of(mapped);
    }

    public FoodDtos.FoodItem getFoodDetail(Long foodId) {
        FoodNutrition food = foodNutritionMapper.selectById(foodId);
        if (food == null || food.getShelfStatus() == null || food.getShelfStatus() != SHELF_ON) {
            throw new BizException(ErrorCode.NOT_FOUND, "食物不存在或已下架");
        }
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

    private Map<Long, String> categoryNameMap() {
        return foodCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(FoodCategory::getId, FoodCategory::getCategoryName, (a, b) -> a));
    }

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
