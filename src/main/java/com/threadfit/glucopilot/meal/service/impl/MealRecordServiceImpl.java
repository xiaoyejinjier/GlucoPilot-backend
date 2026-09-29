package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.service.FoodService;
import com.threadfit.glucopilot.meal.dto.MealDtos;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 饮食记录：按 (recordDate, nodeCode) 覆盖写入、按日查询、逻辑删除。
 * 营养快照保存时点固化、不可变；预测三列仅落库不重算（prediction 客户端串联）。
 */
@Service
@RequiredArgsConstructor
public class MealRecordServiceImpl implements MealRecordService {

    private static final int TEXT_MAX = 512;

    private final MealRecordMapper mealRecordMapper;
    private final MealFoodItemMapper mealFoodItemMapper;
    private final MediaFileService mediaFileService;
    private final FoodService foodService;

    @Override
    @Transactional
    public MealVos.SaveResult save(Long userId, MealDtos.SaveRequest request) {
        if (!GlucoseNodes.isMealNode(request.getNodeCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "nodeCode 必须是餐点节点");
        }
        if (request.getHungerLevel() != null) {
            CodeEnum.of(CodeEnum.HungerLevel.class, request.getHungerLevel());
        }
        Long mediaId = resolveMediaId(userId, request.getMediaId());
        List<MealDtos.Item> items = request.getItems() == null ? List.of() : request.getItems();
        validateFoodIds(items);
        String foodsText = resolveFoodsText(request.getFoodsText(), items);
        BigDecimal carbsG = resolveCarbsG(request.getCarbsG(), items);

        MealRecord existing = mealRecordMapper.selectOne(new LambdaQueryWrapper<MealRecord>()
                .eq(MealRecord::getUserId, userId)
                .eq(MealRecord::getRecordDate, request.getRecordDate())
                .eq(MealRecord::getNodeCode, request.getNodeCode())
                .last("LIMIT 1"));

        LocalDateTime at = LocalDateTime.now();
        Long mealId;
        boolean created;
        if (existing == null) {
            MealRecord row = new MealRecord();
            row.setUserId(userId);
            row.setRecordDate(request.getRecordDate());
            row.setNodeCode(request.getNodeCode());
            row.setMediaId(mediaId);
            row.setFoodsText(foodsText);
            row.setCarbsG(carbsG);
            row.setHungerLevel(request.getHungerLevel());
            row.setSupplementText(trimToMax(request.getSupplementText()));
            row.setPredictNow(request.getPredictNow());
            row.setPredictSuggest(request.getPredictSuggest());
            row.setSuggestText(trimToMax(request.getSuggestText()));
            row.setRecordedAt(at);
            mealRecordMapper.insert(row);
            mealId = row.getId();
            created = true;
        } else {
            existing.setMediaId(mediaId);
            existing.setFoodsText(foodsText);
            existing.setCarbsG(carbsG);
            existing.setHungerLevel(request.getHungerLevel());
            existing.setSupplementText(trimToMax(request.getSupplementText()));
            existing.setPredictNow(request.getPredictNow());
            existing.setPredictSuggest(request.getPredictSuggest());
            existing.setSuggestText(trimToMax(request.getSuggestText()));
            existing.setRecordedAt(at);
            mealRecordMapper.updateById(existing);
            mealId = existing.getId();
            created = false;
        }

        replaceItems(mealId, items);
        return MealVos.SaveResult.builder()
                .id(String.valueOf(mealId))
                .created(created)
                .build();
    }

    @Override
    public MealVos.DayMeals listDay(Long userId, LocalDate recordDate) {
        List<MealRecord> records = mealRecordMapper.selectList(new LambdaQueryWrapper<MealRecord>()
                .eq(MealRecord::getUserId, userId)
                .eq(MealRecord::getRecordDate, recordDate)
                .orderByAsc(MealRecord::getNodeCode)
                .orderByAsc(MealRecord::getId));
        if (records.isEmpty()) {
            return MealVos.DayMeals.builder().recordDate(recordDate).meals(List.of()).build();
        }
        List<Long> mealIds = records.stream().map(MealRecord::getId).toList();
        Map<Long, List<MealFoodItem>> itemsByMeal = mealFoodItemMapper.selectList(
                        new LambdaQueryWrapper<MealFoodItem>()
                                .in(MealFoodItem::getMealId, mealIds)
                                .orderByAsc(MealFoodItem::getSortNo)
                                .orderByAsc(MealFoodItem::getId))
                .stream()
                .collect(Collectors.groupingBy(MealFoodItem::getMealId));

        List<MealVos.MealDetail> meals = records.stream()
                .map(r -> toDetail(r, userId, itemsByMeal.getOrDefault(r.getId(), List.of())))
                .toList();
        return MealVos.DayMeals.builder().recordDate(recordDate).meals(meals).build();
    }

    @Override
    @Transactional
    public void remove(Long userId, Long id) {
        MealRecord row = requireOwned(userId, id);
        SoftDeletes.mark(mealRecordMapper, MealRecord::getId, row.getId(),
                MealRecord::getIsDeleted, MealRecord::getDeletedAt, MealRecord::getDeletedBy);
        markItemsDeleted(row.getId());
    }

    @Override
    public Map<Long, Long> countActiveByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return mealRecordMapper.selectList(new QueryWrapper<MealRecord>()
                        .select("user_id")
                        .in("user_id", userIds))
                .stream()
                .collect(Collectors.groupingBy(MealRecord::getUserId, Collectors.counting()));
    }

    private Long resolveMediaId(Long userId, Long mediaId) {
        if (mediaId == null) {
            return null;
        }
        MediaFile media = mediaFileService.findOwnedMedia(mediaId, userId, MediaBizType.MEAL);
        if (media == null) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "餐食照片不存在或不可用");
        }
        return media.getId();
    }

    private void validateFoodIds(List<MealDtos.Item> items) {
        List<Long> foodIds = items.stream()
                .map(MealDtos.Item::getFoodId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (foodIds.isEmpty()) {
            return;
        }
        Set<Long> available = foodService.listAvailableByIds(foodIds).stream()
                .map(FoodNutrition::getId)
                .collect(Collectors.toSet());
        boolean missing = foodIds.stream().anyMatch(id -> !available.contains(id));
        if (missing) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "食物不存在或已下架");
        }
    }

    private String resolveFoodsText(String foodsText, List<MealDtos.Item> items) {
        if (StringUtils.hasText(foodsText)) {
            return trimToMax(foodsText);
        }
        if (items.isEmpty()) {
            return null;
        }
        return trimToMax(items.stream().map(MealDtos.Item::getFoodName).collect(Collectors.joining("、")));
    }

    private BigDecimal resolveCarbsG(BigDecimal carbsG, List<MealDtos.Item> items) {
        if (carbsG != null) {
            return carbsG;
        }
        boolean any = items.stream().anyMatch(i -> i.getCarbsG() != null);
        if (!any) {
            return null;
        }
        return items.stream()
                .map(MealDtos.Item::getCarbsG)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void replaceItems(Long mealId, List<MealDtos.Item> items) {
        markItemsDeleted(mealId);
        int sortNo = 0;
        for (MealDtos.Item item : items) {
            MealFoodItem row = new MealFoodItem();
            row.setMealId(mealId);
            row.setFoodId(item.getFoodId());
            row.setFoodName(item.getFoodName());
            row.setWeightG(item.getWeightG());
            row.setCarbsG(item.getCarbsG());
            row.setCaloriesKcal(item.getCaloriesKcal());
            row.setSortNo(sortNo++);
            mealFoodItemMapper.insert(row);
        }
    }

    private void markItemsDeleted(Long mealId) {
        LocalDateTime now = LocalDateTime.now();
        long operator = RequestContext.operatorIdOrZero();
        mealFoodItemMapper.update(null, new LambdaUpdateWrapper<MealFoodItem>()
                .eq(MealFoodItem::getMealId, mealId)
                .set(MealFoodItem::getIsDeleted, 1)
                .set(MealFoodItem::getDeletedAt, now)
                .set(MealFoodItem::getDeletedBy, operator));
    }

    private MealRecord requireOwned(Long userId, Long id) {
        MealRecord row = mealRecordMapper.selectById(id);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "饮食记录不存在");
        }
        return row;
    }

    private MealVos.MealDetail toDetail(MealRecord r, Long userId, List<MealFoodItem> items) {
        String mediaUrl = null;
        if (r.getMediaId() != null) {
            MediaFile media = mediaFileService.findOwnedMedia(r.getMediaId(), userId, MediaBizType.MEAL);
            mediaUrl = media == null ? null : media.getFileUrl();
        }
        List<MealVos.MealItemDetail> itemDetails = items.stream().map(this::toItemDetail).toList();
        return MealVos.MealDetail.builder()
                .id(String.valueOf(r.getId()))
                .nodeCode(r.getNodeCode())
                .mediaId(r.getMediaId() == null ? null : String.valueOf(r.getMediaId()))
                .mediaUrl(mediaUrl)
                .foodsText(r.getFoodsText())
                .carbsG(r.getCarbsG())
                .hungerLevel(r.getHungerLevel())
                .supplementText(r.getSupplementText())
                .predictNow(r.getPredictNow())
                .predictSuggest(r.getPredictSuggest())
                .suggestText(r.getSuggestText())
                .recordedAt(r.getRecordedAt())
                .items(itemDetails)
                .build();
    }

    private MealVos.MealItemDetail toItemDetail(MealFoodItem i) {
        return MealVos.MealItemDetail.builder()
                .foodId(i.getFoodId() == null ? null : String.valueOf(i.getFoodId()))
                .foodName(i.getFoodName())
                .weightG(i.getWeightG())
                .carbsG(i.getCarbsG())
                .caloriesKcal(i.getCaloriesKcal())
                .build();
    }

    private static String trimToMax(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= TEXT_MAX ? trimmed : trimmed.substring(0, TEXT_MAX);
    }
}
