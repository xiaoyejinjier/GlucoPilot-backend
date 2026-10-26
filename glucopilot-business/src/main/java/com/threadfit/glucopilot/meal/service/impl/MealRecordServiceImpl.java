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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 饮食记录业务实现。
 * <p>核心语义：
 * <ul>
 *   <li><b>覆盖保存</b>：按 (userId, recordDate, nodeCode) 唯一键定位当天该餐点的活跃记录，命中则 update，否则 insert；
 *       明细采用「旧记录整体逻辑删除 + 重新插入」的重建方式，天然丢弃历史版本；</li>
 *   <li><b>快照固化</b>：入库的 carbsG / 明细营养值均按请求原样落库，不反向 join 营养库重算（禁用字段级 PATCH，
 *       要改就整体重新保存覆盖）；</li>
 *   <li><b>预测三列</b>（predictNow / predictSuggest / suggestText）只校验范围、不改写，由 prediction 客户端串联携带，
 *       即使该模块未上线也可存 null，两模块保持解耦；</li>
 *   <li><b>recordedAt 一律取服务端当前时间</b>，防止客户端伪造记录时点。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MealRecordServiceImpl implements MealRecordService {

    private static final int TEXT_MAX = 512;
    /** 档案汇总高频食物条数上限。 */
    private static final int TOP_FOOD_LIMIT = 4;

    private final MealRecordMapper mealRecordMapper;
    private final MealFoodItemMapper mealFoodItemMapper;
    private final MediaFileService mediaFileService;
    private final FoodService foodService;

    @Override
    @Transactional
    public MealVos.SaveResult save(Long userId, MealDtos.SaveRequest request) {
        // 餐点节点必须在三餐范围内，否则业务拒绝（40001）
        if (!GlucoseNodes.isMealNode(request.getNodeCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "nodeCode 必须是餐点节点");
        }
        // hungerLevel 若传入则必须是 (1饱和 2一般 3微饿) 之一，可空不校验
        if (request.getHungerLevel() != null) {
            CodeEnum.of(CodeEnum.HungerLevel.class, request.getHungerLevel());
        }
        // 照片归属经 file 侧校验（meid 类型）+ 当前用户具有访问权
        Long mediaId = resolveMediaId(userId, request.getMediaId());
        List<MealDtos.Item> items = request.getItems() == null ? List.of() : request.getItems();
        // 明细中的 foodId 需存在于营养库（手输项 foodId 为空，跳过）
        validateFoodIds(items);
        // foodsText / carbsG 若缺省则用 items 汇总兜底
        String foodsText = resolveFoodsText(request.getFoodsText(), items);
        BigDecimal carbsG = resolveCarbsG(request.getCarbsG(), items);

        // 按 (user_id, record_date, node_code) 查当天该餐点的活跃主记录：命中即覆盖更新，否则新建
        MealRecord existing = mealRecordMapper.selectOne(new LambdaQueryWrapper<MealRecord>()
                .eq(MealRecord::getUserId, userId)
                .eq(MealRecord::getRecordDate, request.getRecordDate())
                .eq(MealRecord::getNodeCode, request.getNodeCode())
                .last("LIMIT 1"));

        // recordedAt 统一取服务端当前时间，防客户端伪造；prediction 三列原样落库不校验范围之外的值
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

        // 明细整体重建：先逻辑删除旧项再逐条插入快照
        replaceItems(mealId, items);
        return MealVos.SaveResult.builder()
                .id(String.valueOf(mealId))
                .created(created)
                .build();
    }

    @Override
    public MealVos.DayMeals listDay(Long userId, LocalDate recordDate) {
        // 查询用户当天全部活跃餐点记录，按 nodeCode（餐次先后）排序
        List<MealRecord> records = mealRecordMapper.selectList(new LambdaQueryWrapper<MealRecord>()
                .eq(MealRecord::getUserId, userId)
                .eq(MealRecord::getRecordDate, recordDate)
                .orderByAsc(MealRecord::getNodeCode)
                .orderByAsc(MealRecord::getId));
        if (records.isEmpty()) {
            return MealVos.DayMeals.builder().recordDate(recordDate).meals(List.of()).build();
        }
        // 一次性批量取出当天所有餐点的活跃明细，按 mealId 分组，避免 N+1 查询
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
        // 先校验记录归属当前用户，再主记录+明细一并逻辑删除（is_deleted=1）
        MealRecord row = requireOwned(userId, id);
        SoftDeletes.mark(mealRecordMapper, MealRecord::getId, row.getId(),
                MealRecord::getIsDeleted, MealRecord::getDeletedAt, MealRecord::getDeletedBy);
        markItemsDeleted(row.getId());
    }

    @Override
    public List<MealRecord> listRange(Long userId, LocalDate from, LocalDate to) {
        return mealRecordMapper.selectList(new LambdaQueryWrapper<MealRecord>()
                .eq(MealRecord::getUserId, userId)
                .ge(MealRecord::getRecordDate, from)
                .le(MealRecord::getRecordDate, to)
                .orderByAsc(MealRecord::getRecordDate)
                .orderByAsc(MealRecord::getId));
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

    /**
     * 账号注销：物理删除该用户全部饮食数据。
     * <p>
     * 顺序不可颠倒——{@code meal_food_item} 没有 user_id，只能靠 {@code meal_id}
     * 反查归属；主表一删，明细就永远定位不到了。
     */
    @Override
    @Transactional
    public void purgeAccountData(Long userId) {
        mealFoodItemMapper.purgeByUserId(userId);
        mealRecordMapper.purgeByUserId(userId);
    }

    @Override
    public MealVos.ArchiveSummary archiveSummary(Long userId, LocalDate from, LocalDate to) {
        List<MealRecord> records = mealRecordMapper.selectList(new LambdaQueryWrapper<MealRecord>()
                .eq(MealRecord::getUserId, userId)
                .ge(MealRecord::getRecordDate, from)
                .le(MealRecord::getRecordDate, to));
        int recordDays = (int) records.stream().map(MealRecord::getRecordDate).distinct().count();
        // 均碳：按主记录 carbsG 快照取均餐值（无值记录不参与）
        BigDecimal avgCarbsG = averageOf(records.stream().map(MealRecord::getCarbsG).toList());
        // 均热：明细热量先按餐求和，再取均餐值（无热量明细的餐点不参与）
        List<MealFoodItem> items = records.isEmpty() ? List.of() : mealFoodItemMapper.selectList(
                new LambdaQueryWrapper<MealFoodItem>()
                        .in(MealFoodItem::getMealId, records.stream().map(MealRecord::getId).toList()));
        Map<Long, BigDecimal> calByMeal = items.stream()
                .filter(i -> i.getCaloriesKcal() != null)
                .collect(Collectors.groupingBy(MealFoodItem::getMealId,
                        Collectors.reducing(BigDecimal.ZERO, MealFoodItem::getCaloriesKcal, BigDecimal::add)));
        BigDecimal avgCal = averageOf(new ArrayList<>(calByMeal.values()));
        List<String> topFoods = items.stream()
                .map(MealFoodItem::getFoodName)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_FOOD_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
        return MealVos.ArchiveSummary.builder()
                .recordDays(recordDays)
                .avgCarbsG(avgCarbsG)
                .avgCal(avgCal)
                .topFoods(topFoods)
                .build();
    }

    /** 非空数值均值，整数口径（HALF_UP）；无有效值返回 null。 */
    private static BigDecimal averageOf(List<BigDecimal> values) {
        List<BigDecimal> valid = values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return null;
        }
        BigDecimal sum = valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(valid.size()), 0, RoundingMode.HALF_UP);
    }

    /** 校验照片归属：mediaId 可空；非空时须是本用户、餐食业务类型的可用照片。 */
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

    /** 校验明细 foodId 存在性：去重后批量查询，任一 foodId 不存在/已下架即拒绝；全部为空（手输）则跳过。 */
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

    /** 食物简述：优先用入参 foodsText；否则用明细 foodName 拼接兜底；都没有则 null。 */
    private String resolveFoodsText(String foodsText, List<MealDtos.Item> items) {
        if (StringUtils.hasText(foodsText)) {
            return trimToMax(foodsText);
        }
        if (items.isEmpty()) {
            return null;
        }
        return trimToMax(items.stream().map(MealDtos.Item::getFoodName).collect(Collectors.joining("、")));
    }

    /** 碳水化合物：优先用入参 carbsG；否则把明细 carbsG 求和兜底；明细也没有则 null。 */
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

    /** 明细重建：先把旧项全部逻辑删除，再按请求顺序逐条插入新快照并赋予递增 sortNo。 */
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

    /** 将 mealId 下所有明细统一标记为逻辑删除，保留审计（删除时间/操作人）。 */
    private void markItemsDeleted(Long mealId) {
        LocalDateTime now = LocalDateTime.now();
        long operator = RequestContext.operatorIdOrZero();
        mealFoodItemMapper.update(null, new LambdaUpdateWrapper<MealFoodItem>()
                .eq(MealFoodItem::getMealId, mealId)
                .set(MealFoodItem::getIsDeleted, 1)
                .set(MealFoodItem::getDeletedAt, now)
                .set(MealFoodItem::getDeletedBy, operator));
    }

    /** 归属校验：记录必须存在且属于当前用户，否则按未找到处理。 */
    private MealRecord requireOwned(Long userId, Long id) {
        MealRecord row = mealRecordMapper.selectById(id);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "饮食记录不存在");
        }
        return row;
    }

    /** 组装单条餐点详情；媒体文件一并按当前用户鉴权后返回其可访问 URL。 */
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

    /** 组装单条明细 VO（id 导出为字符串以兼容前端 id 类型）。 */
    private MealVos.MealItemDetail toItemDetail(MealFoodItem i) {
        return MealVos.MealItemDetail.builder()
                .foodId(i.getFoodId() == null ? null : String.valueOf(i.getFoodId()))
                .foodName(i.getFoodName())
                .weightG(i.getWeightG())
                .carbsG(i.getCarbsG())
                .caloriesKcal(i.getCaloriesKcal())
                .build();
    }

    /** 文本裁剪到最大长度：去首尾空白、空串归一为 null，超长截断。 */
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
