package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.model.BaseEntity;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** B 端营养库：分类与食物的增删改查。 */
@Service
@RequiredArgsConstructor
public class AdminFoodService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FoodCategoryMapper foodCategoryMapper;
    private final FoodNutritionMapper foodNutritionMapper;

    public List<AdminFoodDtos.FoodCategoryItem> listCategories() {
        return foodCategoryMapper.selectList(new LambdaQueryWrapper<FoodCategory>()
                        .orderByAsc(FoodCategory::getSortNo)
                        .orderByAsc(FoodCategory::getId))
                .stream()
                .map(c -> AdminFoodDtos.FoodCategoryItem.builder()
                        .id(c.getId())
                        .name(c.getCategoryName())
                        .sort(c.getSortNo())
                        .build())
                .toList();
    }

    @Transactional
    public AdminFoodDtos.FoodCategoryItem saveCategory(AdminFoodDtos.FoodCategorySaveRequest req) {
        FoodCategory row = req.getId() == null ? new FoodCategory() : requireCategory(req.getId());
        row.setCategoryName(req.getName().trim());
        row.setSortNo(req.getSort() == null ? 0 : req.getSort());
        if (row.getIsEnabled() == null) {
            row.setIsEnabled(1);
        }
        if (req.getId() == null) {
            foodCategoryMapper.insert(row);
        } else {
            foodCategoryMapper.updateById(row);
        }
        return AdminFoodDtos.FoodCategoryItem.builder()
                .id(row.getId())
                .name(row.getCategoryName())
                .sort(row.getSortNo())
                .build();
    }

    @Transactional
    public void removeCategory(Long id) {
        long used = foodNutritionMapper.selectCount(new LambdaQueryWrapper<FoodNutrition>()
                .eq(FoodNutrition::getCategoryId, id));
        if (used > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "该分类下仍有食物，请先移动或删除食物");
        }
        softDelete(foodCategoryMapper, requireCategory(id).getId(),
                FoodCategory::getId, FoodCategory::getIsDeleted, FoodCategory::getDeletedAt, FoodCategory::getDeletedBy);
    }

    public List<AdminFoodDtos.FoodItem> listFoods() {
        Map<Long, String> catNames = foodCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(FoodCategory::getId, FoodCategory::getCategoryName, (a, b) -> a));
        return foodNutritionMapper.selectList(new LambdaQueryWrapper<FoodNutrition>()
                        .orderByDesc(FoodNutrition::getId))
                .stream().map(f -> toFood(f, catNames)).toList();
    }

    @Transactional
    public AdminFoodDtos.FoodItem saveFood(AdminFoodDtos.FoodSaveRequest req) {
        FoodCategory cat = requireCategory(req.getCategoryId());
        FoodNutrition row = req.getId() == null ? new FoodNutrition() : requireFood(req.getId());
        row.setFoodName(req.getName().trim());
        row.setCategoryId(cat.getId());
        row.setCategoryCode(cat.getId().intValue() <= 9 ? cat.getId().intValue() : 9);
        row.setCarbsPer100g(req.getCarbs() == null ? BigDecimal.ZERO : req.getCarbs());
        row.setCaloriesPer100g(req.getCal() == null ? BigDecimal.ZERO : req.getCal());
        row.setGiValue(req.getGi());
        row.setImageUrl(req.getImage());
        row.setShelfStatus(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        if (req.getId() == null) {
            foodNutritionMapper.insert(row);
        } else {
            foodNutritionMapper.updateById(row);
        }
        return toFood(requireFood(row.getId()), Map.of(cat.getId(), cat.getCategoryName()));
    }

    @Transactional
    public void removeFood(Long id) {
        softDelete(foodNutritionMapper, requireFood(id).getId(),
                FoodNutrition::getId, FoodNutrition::getIsDeleted, FoodNutrition::getDeletedAt, FoodNutrition::getDeletedBy);
    }

    private static <T extends BaseEntity> void softDelete(
            BaseMapper<T> mapper,
            Long id,
            SFunction<T, ?> idGetter,
            SFunction<T, Integer> isDeletedGetter,
            SFunction<T, LocalDateTime> deletedAtGetter,
            SFunction<T, Long> deletedByGetter) {
        SoftDeletes.mark(mapper, idGetter, id, isDeletedGetter, deletedAtGetter, deletedByGetter);
    }

    private AdminFoodDtos.FoodItem toFood(FoodNutrition f, Map<Long, String> catNames) {
        return AdminFoodDtos.FoodItem.builder()
                .id(f.getId())
                .name(f.getFoodName())
                .categoryId(f.getCategoryId())
                .categoryName(f.getCategoryId() == null ? "" : catNames.getOrDefault(f.getCategoryId(), ""))
                .carbs(f.getCarbsPer100g())
                .cal(f.getCaloriesPer100g())
                .gi(f.getGiValue())
                .image(f.getImageUrl() == null ? "" : f.getImageUrl())
                .description("")
                .enabled(f.getShelfStatus() == null || f.getShelfStatus() == 1)
                .updatedAt(fmt(f.getGmtModified()))
                .build();
    }

    private FoodCategory requireCategory(Long id) {
        FoodCategory row = foodCategoryMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        return row;
    }

    private FoodNutrition requireFood(Long id) {
        FoodNutrition row = foodNutritionMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "食物不存在");
        }
        return row;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }
}
