package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
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
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台食品营养库业务层。
 *
 * <p>负责食品分类和食品的查询、新增、修改与逻辑删除，并集中处理
 * “分类必须存在”“分类下有食品时不能删除”等业务规则。写操作使用事务，
 * 确保一次业务操作要么全部成功，要么全部回滚。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminFoodService {

    /** 管理端列表展示修改时间时使用的格式。 */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 访问 food_category 表。 */
    private final FoodCategoryMapper foodCategoryMapper;

    /** 访问 food_nutrition 表。 */
    private final FoodNutritionMapper foodNutritionMapper;

    /** 查询全部未逻辑删除分类，并转换成管理端分类 DTO。 */
    public List<AdminFoodDtos.FoodCategoryItem> listCategories() {
        return foodCategoryMapper.selectList(new LambdaQueryWrapper<FoodCategory>()
                        .orderByAsc(FoodCategory::getSortNo)
                        .orderByAsc(FoodCategory::getId))
                .stream()
                .map(c -> AdminFoodDtos.FoodCategoryItem.builder()
                        .id(c.getId())
                        .name(c.getCategoryName())
                        .image(c.getImageUrl() == null ? "" : c.getImageUrl())
                        .sort(c.getSortNo())
                        .build())
                .toList();
    }

    /**
     * 新增或修改食品分类。
     * 请求没有 id 时执行新增；请求带 id 时先确认分类存在，再执行修改。
     */
    @Transactional
    public AdminFoodDtos.FoodCategoryItem saveCategory(AdminFoodDtos.FoodCategorySaveRequest req) {
        // 新增时创建空 Entity；修改时读取原数据，避免修改不存在的分类。
        FoodCategory row = req.getId() == null ? new FoodCategory() : requireCategory(req.getId());

        // 去除分类名称首尾空格，排序未填写时按 0 处理。
        row.setCategoryName(req.getName().trim());
        if (req.getImage() != null) {
            row.setImageUrl(req.getImage().trim());
        }
        row.setSortNo(req.getSort() == null ? 0 : req.getSort());

        // 新分类默认启用；修改时保留原来的启用状态。
        if (row.getIsEnabled() == null) {
            row.setIsEnabled(1);
        }

        // 根据是否存在 id 选择 INSERT 或 UPDATE。
        if (req.getId() == null) {
            foodCategoryMapper.insert(row);
        } else {
            foodCategoryMapper.updateById(row);
        }

        // 回读持久化结果，不能把仅存在于内存中的图片值当成保存成功。
        FoodCategory saved = requireCategory(row.getId());
        if (req.getImage() != null && !req.getImage().trim().equals(saved.getImageUrl())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "分类图片保存校验失败，请重试");
        }
        return AdminFoodDtos.FoodCategoryItem.builder()
                .id(saved.getId())
                .name(saved.getCategoryName())
                .image(saved.getImageUrl() == null ? "" : saved.getImageUrl())
                .sort(saved.getSortNo())
                .build();
    }

    /**
     * 逻辑删除食品分类。
     * 分类下仍存在食品时拒绝删除，避免食品记录失去有效分类。
     */
    @Transactional
    public void removeCategory(Long id) {
        // MyBatis-Plus 自动忽略已经逻辑删除的食品。
        long used = foodNutritionMapper.selectCount(new LambdaQueryWrapper<FoodNutrition>()
                .eq(FoodNutrition::getCategoryId, id));
        if (used > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "该分类下仍有食物，请先移动或删除食物");
        }
        // 同时写入 is_deleted、deleted_at、deleted_by，满足数据库审计约束。
        softDelete(foodCategoryMapper, requireCategory(id).getId(),
                FoodCategory::getId, FoodCategory::getIsDeleted, FoodCategory::getDeletedAt, FoodCategory::getDeletedBy);
    }

    /** 查询全部未删除食品，并补充分类名称和管理端展示字段。 */
    public List<AdminFoodDtos.FoodItem> listFoods() {
        // 先构造分类名称映射，避免每转换一条食品就查询一次分类表。
        Map<Long, String> catNames = foodCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(FoodCategory::getId, FoodCategory::getCategoryName, (a, b) -> a));

        // 新创建的食品排在前面，再逐条转换为管理端 DTO。
        return foodNutritionMapper.selectList(new LambdaQueryWrapper<FoodNutrition>()
                        .orderByDesc(FoodNutrition::getId))
                .stream().map(f -> toFood(f, catNames)).toList();
    }

    /**
     * 按管理端筛选条件分页查询食品。筛选和分页均在数据库执行，避免管理端
     * 先拉取固定数量的数据后遗漏记录。
     */
    public PageResult<AdminFoodDtos.FoodItem> pageFoods(
            Long categoryId, String keyword, Boolean enabled, PageQuery query) {
        LambdaQueryWrapper<FoodNutrition> wrapper = new LambdaQueryWrapper<FoodNutrition>()
                .eq(categoryId != null, FoodNutrition::getCategoryId, categoryId)
                .like(StringUtils.hasText(keyword), FoodNutrition::getFoodName,
                        StringUtils.hasText(keyword) ? keyword.trim() : null)
                .eq(enabled != null, FoodNutrition::getShelfStatus,
                        enabled == null ? null : (enabled ? 1 : 0))
                .orderByDesc(FoodNutrition::getId);

        Page<FoodNutrition> page = foodNutritionMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        Map<Long, String> catNames = foodCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(FoodCategory::getId, FoodCategory::getCategoryName, (a, b) -> a));
        List<AdminFoodDtos.FoodItem> records = page.getRecords().stream()
                .map(food -> toFood(food, catNames))
                .toList();
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /**
     * 新增或修改食品。
     * 请求没有 id 时新增；请求带 id 时修改现有食品。
     */
    @Transactional
    public AdminFoodDtos.FoodItem saveFood(AdminFoodDtos.FoodSaveRequest req) {
        // 食品必须属于一个真实存在、未删除的分类。
        FoodCategory cat = requireCategory(req.getCategoryId());

        // 新增时创建新 Entity；修改时读取旧记录。
        FoodNutrition row = req.getId() == null ? new FoodNutrition() : requireFood(req.getId());

        // 将管理端请求字段映射到数据库实体。
        row.setFoodName(req.getName().trim());
        row.setCategoryId(cat.getId());

        // 当前兼容规则：分类 ID 1~9 直接作为编码，大于 9 时归入“其他”编码 9。
        row.setCategoryCode(cat.getId().intValue() <= 9 ? cat.getId().intValue() : 9);

        // 营养值未填写时按 0 保存，GI 允许为空。
        row.setCarbsPer100g(req.getCarbs() == null ? BigDecimal.ZERO : req.getCarbs());
        row.setCaloriesPer100g(req.getCal() == null ? BigDecimal.ZERO : req.getCal());
        row.setGiValue(req.getGi());

        // 当前只保存图片 URL；图片文件上传由独立文件服务负责。
        row.setImageUrl(req.getImage());

        // enabled 明确为 false 时下架，其余情况默认上架。
        row.setShelfStatus(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        if (row.getNutritionSource() == null) row.setNutritionSource("MANUAL");
        if (Boolean.TRUE.equals(req.getEnabled()) && "AI_ESTIMATE".equals(row.getNutritionSource())) {
            row.setNutritionSource("AI_REVIEWED");
        }

        // 根据 id 是否存在选择 INSERT 或 UPDATE。
        if (req.getId() == null) {
            foodNutritionMapper.insert(row);
        } else {
            foodNutritionMapper.updateById(row);
        }

        // 重新读取保存结果，确保返回值包含数据库自动填充的审计字段。
        return toFood(requireFood(row.getId()), Map.of(cat.getId(), cat.getCategoryName()));
    }

    /** 逻辑删除指定食品，并记录删除时间和操作人。 */
    @Transactional
    public void removeFood(Long id) {
        softDelete(foodNutritionMapper, requireFood(id).getId(),
                FoodNutrition::getId, FoodNutrition::getIsDeleted, FoodNutrition::getDeletedAt, FoodNutrition::getDeletedBy);
    }

    /**
     * 对分类和食品复用同一套逻辑删除方法。
     * SFunction 参数告诉公共工具每个审计字段对应哪个 Java getter。
     */
    private static <T extends BaseEntity> void softDelete(
            BaseMapper<T> mapper,
            Long id,
            SFunction<T, ?> idGetter,
            SFunction<T, Integer> isDeletedGetter,
            SFunction<T, LocalDateTime> deletedAtGetter,
            SFunction<T, Long> deletedByGetter) {
        SoftDeletes.mark(mapper, idGetter, id, isDeletedGetter, deletedAtGetter, deletedByGetter);
    }

    /** 将食品数据库实体转换成管理端页面使用的 DTO。 */
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
                .nutritionSource(f.getNutritionSource() == null ? "MANUAL" : f.getNutritionSource())
                .sourceModel(f.getSourceModel())
                .reviewImage(FoodReviewImageService.isPending(f) && f.getSourceMediaId() != null
                        ? "/admin/v1/foods/" + f.getId() + "/review-image" : "")
                .build();
    }

    /** 查询分类；不存在时抛出统一的 404 业务异常。 */
    private FoodCategory requireCategory(Long id) {
        FoodCategory row = foodCategoryMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        return row;
    }

    /** 查询食品；不存在时抛出统一的 404 业务异常。 */
    private FoodNutrition requireFood(Long id) {
        FoodNutrition row = foodNutritionMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "食物不存在");
        }
        return row;
    }

    /** 将时间转换为管理端需要的字符串；时间为空时返回空字符串。 */
    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }
}
