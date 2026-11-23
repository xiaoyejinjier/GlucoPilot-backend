package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import com.threadfit.glucopilot.food.service.FoodService;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.record.service.GlucoseRecordService;
import com.threadfit.glucopilot.meal.dto.MealDtos;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.service.MealAnalysisGenerator;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.service.MealSnapshotMatcher;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.support.MealAnalysisTexts;
import com.threadfit.glucopilot.meal.vo.MealVos;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 *
 * <p>核心语义：
 *
 * <ul>
 *   <li><b>覆盖保存</b>：按 (userId, recordDate, nodeCode) 唯一键定位当天该餐点的活跃记录，命中则 update，否则 insert；
 *       明细采用「旧记录整体逻辑删除 + 重新插入」的重建方式，天然丢弃历史版本；
 *   <li><b>快照固化</b>：入库的 carbsG / 明细克重、热量按请求原样落库（不按 per-100g 重算）；
 *       「分类 / GI / 营养来源」自阶段 2b-3 起由 {@link MealNutritionResolver} 按营养库归一化后落库，
 *       避免客户端伪造营养值；
 *   <li><b>预测字段已下线</b>：请求中的预测字段仅为兼容旧客户端而保留解析，服务端不落库、详情不返回；
 *       历史预测列保留为只读，阶段 4 已归档到 meal_prediction_archive；
 *   <li><b>recordedAt 一律取服务端当前时间</b>，防止客户端伪造记录时点。
 * </ul>
 */
@Slf4j
@Service
public class MealRecordServiceImpl implements MealRecordService {

    private static final int TEXT_MAX = 512;

    /** 档案汇总高频食物条数上限。 */
    private static final int TOP_FOOD_LIMIT = 4;

    private final MealRecordMapper mealRecordMapper;
    private final MealFoodItemMapper mealFoodItemMapper;
    private final MediaFileService mediaFileService;
    private final FoodService foodService;
    /** 营养归一化（只读 food_nutrition），保存路径用 clientValuesFirst=false。 */
    private final MealNutritionResolver nutritionResolver;
    /** 只读查询营养库，用于校验 libraryFoodId 是否归属本餐照片。 */
    private final FoodNutritionMapper foodNutritionMapper;
    /** 识别快照（只读），用于低置信拦截与 library_status 对齐。 */
    private final MealAnalysisSnapshotStore analysisSnapshots;
    /** 保存后分析：提交后独立事务生成，失败只置 FAILED。 */
    private final MealAnalysisGenerator analysisGenerator;
    /** 只读血糖记录，用于查询时组装餐前/餐后上下文。 */
    private final GlucoseRecordService glucoseRecordService;
    /** 主记录 + 明细的保存事务模板（分析在事务提交后执行）。 */
    private final TransactionTemplate txTemplate;

    public MealRecordServiceImpl(
            MealRecordMapper mealRecordMapper,
            MealFoodItemMapper mealFoodItemMapper,
            MediaFileService mediaFileService,
            FoodService foodService,
            MealNutritionResolver nutritionResolver,
            FoodNutritionMapper foodNutritionMapper,
            MealAnalysisSnapshotStore analysisSnapshots,
            MealAnalysisGenerator analysisGenerator,
            GlucoseRecordService glucoseRecordService,
            PlatformTransactionManager transactionManager) {
        this.mealRecordMapper = mealRecordMapper;
        this.mealFoodItemMapper = mealFoodItemMapper;
        this.mediaFileService = mediaFileService;
        this.foodService = foodService;
        this.nutritionResolver = nutritionResolver;
        this.foodNutritionMapper = foodNutritionMapper;
        this.analysisSnapshots = analysisSnapshots;
        this.analysisGenerator = analysisGenerator;
        this.glucoseRecordService = glucoseRecordService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 低置信阈值：与识别链路共用同一个配置项（一值两用，属已知技术债）。
     * 字段级 @Value 并带默认值，保证直接 new 出来的实例行为不变。
     */
    @Value("${ai.meal.low-confidence-threshold:70}")
    private int lowConfidenceThreshold = 70;

    @Override
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
        // 阶段 2b-3a：保存路径按营养库归一化（clientValuesFirst=false，库值覆盖客户端值以防伪造）。
        // resolver 只读 food_nutrition，不建草稿、不写库；confidence 由后续分析快照提供，此处不填。
        List<ItemInput> normalized = nutritionResolver.resolve(toInputs(items), false);
        // 识别草稿归属校验：库内行的 source_media_id 必须是本餐照片或为空，否则摘掉悬空引用
        detachForeignLibraryRefs(normalized, mediaId);

        // 阶段 2b-3b：识别快照 → 低置信拦截。
        // token 命中且 userId/mediaId 都匹配时才认账；过期、跨用户、换照片一律按「无 token」降级，不拒绝保存。
        MealAnalysisSnapshotStore.Snapshot snapshot =
                loadSnapshot(userId, request.getAnalysisToken(), mediaId);
        List<String> lowConfidence =
                MealSnapshotMatcher.alignAndEvaluate(normalized, snapshot, lowConfidenceThreshold);
        if (!lowConfidence.isEmpty() && !Boolean.TRUE.equals(request.getConfirmLowConfidence())) {
            if (snapshot != null) {
                // 新客户端：必须每次保存都显式确认（同一 token 不沿用上一次的确认结果）
                log.warn("低置信未确认，拒绝保存：userId={} recordDate={} nodeCode={} items={}",
                        userId, request.getRecordDate(), request.getNodeCode(), lowConfidence);
                throw new BizException(ErrorCode.BIZ_REJECTED,
                        "存在未确认的低置信项：" + String.join("、", lowConfidence) + "，请确认后重试");
            }
            // 旧客户端 / 快照不可用：没有确认交互，硬拦会造成流程断裂，放行并留痕
            log.warn("无快照保存，跳过低置信拦截：userId={} recordDate={} nodeCode={} clientVersion={} 低置信项={}",
                    userId, request.getRecordDate(), request.getNodeCode(),
                    request.getClientVersion(), lowConfidence.size());
        }
        // 仅在快照通过校验时才落 token；过期/不一致留 null，避免出现「有 token 但未验证」的脏数据
        String analysisToken = snapshot == null ? null : request.getAnalysisToken();
        // 阶段 3b：识别元信息同样只认校验通过的快照；无快照（手输/旧客户端）一律置空，不残留上次识别
        String recognitionModel = snapshot == null ? null : snapshot.getModel();
        LocalDateTime recognitionAt = snapshot == null ? null : toLocalDateTime(snapshot.getIssuedAt());
        // foodsText / carbsG 若缺省则用 items 汇总兜底
        String foodsText = resolveFoodsText(request.getFoodsText(), items);
        BigDecimal carbsG = resolveCarbsG(request.getCarbsG(), items);

        // 主记录 + 明细在同一事务内持久化；分析在提交后独立执行（失败只置 FAILED，不回滚保存）
        SaveOutcome outcome =
                txTemplate.execute(
                        status ->
                                persist(
                                        userId,
                                        request,
                                        normalized,
                                        mediaId,
                                        foodsText,
                                        carbsG,
                                        analysisToken,
                                        recognitionModel,
                                        recognitionAt));

        MealAnalysisGenerator.Result analysisResult =
                analysisGenerator.generate(userId, outcome.mealId(), outcome.previousRuleVersion());
        return MealVos.SaveResult.builder()
                .id(String.valueOf(outcome.mealId()))
                .created(outcome.created())
                .analysisStatus(
                        analysisResult == null
                                ? MealAnalysisGenerator.STATUS_PENDING
                                : analysisResult.getStatus())
                .ruleVersionChanged(analysisResult != null && analysisResult.isRuleVersionChanged())
                .analysis(analysisResult == null ? null : analysisResult.getAnalysis())
                .build();
    }

    /** 持久化结果：mealId + 是否新建 + 保存前使用的规则版本（用于 ruleVersionChanged）。 */
    private record SaveOutcome(Long mealId, boolean created, String previousRuleVersion) {
    }

    /**
     * 主记录与明细的持久化（事务模板内执行）。
     * <p>覆盖更新时统一重置分析列：明细已整体重建，旧分析结论必然失效，等待保存后的分析器重新生成。
     */
    private SaveOutcome persist(
            Long userId,
            MealDtos.SaveRequest request,
            List<ItemInput> normalized,
            Long mediaId,
            String foodsText,
            BigDecimal carbsG,
            String analysisToken,
            String recognitionModel,
            LocalDateTime recognitionAt) {
        // 按 (user_id, record_date, node_code) 查当天该餐点的活跃主记录：命中即覆盖更新，否则新建
        MealRecord existing =
                mealRecordMapper.selectOne(
                        new LambdaQueryWrapper<MealRecord>()
                                .eq(MealRecord::getUserId, userId)
                                .eq(MealRecord::getRecordDate, request.getRecordDate())
                                .eq(MealRecord::getNodeCode, request.getNodeCode())
                                .last("LIMIT 1"));

        // recordedAt 统一取服务端当前时间，防客户端伪造；预测字段已停写（兼容解析但忽略）
        LocalDateTime at = LocalDateTime.now();
        Long mealId;
        boolean created;
        String previousRuleVersion = null;
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
            row.setAnalysisToken(analysisToken);
            row.setAnalysisStatus(0);
            // 阶段 3b：识别模型/识别时刻来自快照，确认时刻即本次保存的服务端时间
            row.setRecognitionModel(recognitionModel);
            row.setRecognitionAt(recognitionAt);
            row.setConfirmedAt(at);
            row.setRecordedAt(at);
            mealRecordMapper.insert(row);
            mealId = row.getId();
            created = true;
        } else {
            previousRuleVersion = existing.getAnalysisRuleVersion();
            existing.setMediaId(mediaId);
            existing.setFoodsText(foodsText);
            existing.setCarbsG(carbsG);
            existing.setHungerLevel(request.getHungerLevel());
            existing.setSupplementText(trimToMax(request.getSupplementText()));
            existing.setAnalysisToken(analysisToken);
            existing.setRecordedAt(at);
            mealRecordMapper.updateById(existing);
            // updateById 忽略 null 字段：令牌清空、分析列重置必须用显式 UPDATE 才会生效
            mealRecordMapper.update(null,
                    new LambdaUpdateWrapper<MealRecord>()
                            .eq(MealRecord::getId, existing.getId())
                            .set(MealRecord::getAnalysisToken, analysisToken)
                            .set(MealRecord::getAnalysisStatus, 0)
                            // 识别元信息同令牌：必须显式 set，才能在无快照覆盖保存时把上次的值清空
                            .set(MealRecord::getRecognitionModel, recognitionModel)
                            .set(MealRecord::getRecognitionAt, recognitionAt)
                            .set(MealRecord::getConfirmedAt, at)
                            .set(MealRecord::getAnalysisTier, null)
                            .set(MealRecord::getAnalysisSummary, null)
                            .set(MealRecord::getAnalysisRuleVersion, null)
                            .set(MealRecord::getAnalysisGeneratedAt, null));
            mealId = existing.getId();
            created = false;
        }

        // 明细整体重建：先逻辑删除旧项再逐条插入归一化后的快照
        replaceItems(mealId, normalized);
        return new SaveOutcome(mealId, created, previousRuleVersion);
    }

    @Override
    public MealVos.DayMeals listDay(Long userId, LocalDate recordDate) {
        // 查询用户当天全部活跃餐点记录，按 nodeCode（餐次先后）排序
        List<MealRecord> records =
                mealRecordMapper.selectList(
                        new LambdaQueryWrapper<MealRecord>()
                                .eq(MealRecord::getUserId, userId)
                                .eq(MealRecord::getRecordDate, recordDate)
                                .orderByAsc(MealRecord::getNodeCode)
                                .orderByAsc(MealRecord::getId));
        if (records.isEmpty()) {
            return MealVos.DayMeals.builder().recordDate(recordDate).meals(List.of()).build();
        }
        // 一次性批量取出当天所有餐点的活跃明细，按 mealId 分组，避免 N+1 查询
        List<Long> mealIds = records.stream().map(MealRecord::getId).toList();
        Map<Long, List<MealFoodItem>> itemsByMeal =
                mealFoodItemMapper
                        .selectList(
                                new LambdaQueryWrapper<MealFoodItem>()
                                        .in(MealFoodItem::getMealId, mealIds)
                                        .orderByAsc(MealFoodItem::getSortNo)
                                        .orderByAsc(MealFoodItem::getId))
                        .stream()
                        .collect(Collectors.groupingBy(MealFoodItem::getMealId));

        // 血糖上下文只读：一次取出当天全部血糖节点，按 nodeCode 建索引（缺失节点不参与）
        Map<String, BigDecimal> glucoseByNode =
                glucoseRecordService.listRange(userId, recordDate, recordDate).stream()
                        .filter(g -> g.getGlucoseValue() != null)
                        .collect(
                                Collectors.toMap(
                                        GlucoseRecord::getNodeCode,
                                        GlucoseRecord::getGlucoseValue,
                                        (first, second) -> first));

        List<MealVos.MealDetail> meals =
                records.stream()
                        .map(
                                r ->
                                        toDetail(
                                                r,
                                                userId,
                                                itemsByMeal.getOrDefault(r.getId(), List.of()),
                                                glucoseByNode))
                        .toList();
        return MealVos.DayMeals.builder().recordDate(recordDate).meals(meals).build();
    }

    @Override
    @Transactional
    public void remove(Long userId, Long id) {
        // 先校验记录归属当前用户，再主记录+明细一并逻辑删除（is_deleted=1）
        MealRecord row = requireOwned(userId, id);
        SoftDeletes.mark(
                mealRecordMapper,
                MealRecord::getId,
                row.getId(),
                MealRecord::getIsDeleted,
                MealRecord::getDeletedAt,
                MealRecord::getDeletedBy);
        markItemsDeleted(row.getId());
    }

    @Override
    public List<MealRecord> listRange(Long userId, LocalDate from, LocalDate to) {
        return mealRecordMapper.selectList(
                new LambdaQueryWrapper<MealRecord>()
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
        return mealRecordMapper
                .selectList(new QueryWrapper<MealRecord>().select("user_id").in("user_id", userIds))
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
        List<MealRecord> records =
                mealRecordMapper.selectList(
                        new LambdaQueryWrapper<MealRecord>()
                                .eq(MealRecord::getUserId, userId)
                                .ge(MealRecord::getRecordDate, from)
                                .le(MealRecord::getRecordDate, to));
        int recordDays = (int) records.stream().map(MealRecord::getRecordDate).distinct().count();
        // 均碳：按主记录 carbsG 快照取均餐值（无值记录不参与）
        BigDecimal avgCarbsG = averageOf(records.stream().map(MealRecord::getCarbsG).toList());
        // 均热：明细热量先按餐求和，再取均餐值（无热量明细的餐点不参与）
        List<MealFoodItem> items =
                records.isEmpty()
                        ? List.of()
                        : mealFoodItemMapper.selectList(
                                new LambdaQueryWrapper<MealFoodItem>()
                                        .in(
                                                MealFoodItem::getMealId,
                                                records.stream().map(MealRecord::getId).toList()));
        Map<Long, BigDecimal> calByMeal =
                items.stream()
                        .filter(i -> i.getCaloriesKcal() != null)
                        .collect(
                                Collectors.groupingBy(
                                        MealFoodItem::getMealId,
                                        Collectors.reducing(
                                                BigDecimal.ZERO,
                                                MealFoodItem::getCaloriesKcal,
                                                BigDecimal::add)));
        BigDecimal avgCal = averageOf(new ArrayList<>(calByMeal.values()));
        List<String> topFoods =
                items.stream()
                        .map(MealFoodItem::getFoodName)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                        .entrySet()
                        .stream()
                        .sorted(
                                Map.Entry.<String, Long>comparingByValue()
                                        .reversed()
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
        List<Long> foodIds =
                items.stream()
                        .map(MealDtos.Item::getFoodId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (foodIds.isEmpty()) {
            return;
        }
        Set<Long> available =
                foodService.listAvailableByIds(foodIds).stream()
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
        return trimToMax(
                items.stream().map(MealDtos.Item::getFoodName).collect(Collectors.joining("、")));
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

    /** 明细重建：先把旧项全部逻辑删除，再按顺序逐条插入归一化后的快照并赋予递增 sortNo。 */
    private void replaceItems(Long mealId, List<ItemInput> items) {
        markItemsDeleted(mealId);
        int sortNo = 0;
        for (ItemInput item : items) {
            MealFoodItem row = new MealFoodItem();
            row.setMealId(mealId);
            row.setFoodId(item.getFoodId());
            row.setLibraryFoodId(item.getLibraryFoodId());
            row.setFoodName(item.getFoodName());
            // 克重 / 碳水 / 热量沿用客户端快照（resolver 不改这三项，也不按 per-100g 重算）
            row.setWeightG(item.getWeightG());
            row.setCarbsG(item.getCarbsG());
            row.setCaloriesKcal(item.getCaloriesKcal());
            applyNutritionSnapshot(item, row);
            row.setSortNo(sortNo++);
            mealFoodItemMapper.insert(row);
        }
    }

    /** 分类 / GI / 来源快照：GI 在本阶段只区分「库里有值(LIBRARY) / 无值(NONE)」。 */
    private static void applyNutritionSnapshot(ItemInput item, MealFoodItem row) {
        row.setCategoryCode(item.getCategoryCode());
        row.setGiValue(item.getGiValue());
        row.setGiSource(item.getGiValue() != null ? "LIBRARY" : "NONE");
        row.setNutritionSource(item.getNutritionSource());
        // 识别时的库匹配状态：快照命中已由 MealSnapshotMatcher 回填到 item，否则用归一化结果
        row.setLibraryStatus(item.getLibraryStatus());
        // 保存路径不采信客户端置信度（防伪造）；真正的 confidence 由 2b-3b 的分析快照注入
        row.setConfidence(null);
        row.setUserCorrected(Boolean.TRUE.equals(item.getUserCorrected()) ? 1 : 0);
    }

    /** 请求明细 → resolver 输入（只搬运，不做任何补全）。 */
    private static List<ItemInput> toInputs(List<MealDtos.Item> items) {
        return items.stream().map(source -> {
            ItemInput target = new ItemInput();
            target.setFoodId(source.getFoodId());
            target.setLibraryFoodId(source.getLibraryFoodId());
            target.setFoodName(source.getFoodName());
            target.setCategoryCode(source.getCategoryCode());
            target.setWeightG(source.getWeightG());
            target.setCarbsG(source.getCarbsG());
            target.setCaloriesKcal(source.getCaloriesKcal());
            target.setGiValue(source.getGiValue());
            target.setNutritionSource(source.getNutritionSource());
            target.setConfidence(source.getConfidence());
            target.setUserCorrected(source.getUserCorrected());
            return target;
        }).toList();
    }

    /**
     * 摘掉不属于本餐的识别草稿引用。
     *
     * <p>{@code library_food_id} 指向的可能是一次 AI 识别产生的待审行，若其 {@code source_media_id}
     * 既不为空、又不等于本餐照片，说明客户端把别的餐的草稿挂了过来（复用得手）。此时按未命中处理：
     * 清空引用并置 {@code UNRESOLVED}，与 resolver 对悬空引用的处理保持一致。
     */
    private void detachForeignLibraryRefs(List<ItemInput> items, Long mediaId) {
        List<Long> ids = items.stream()
                .map(ItemInput::getLibraryFoodId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // 明细里没有 libraryFoodId 时一次库都不查（纯手输/纯库内食物场景）
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Long> sourceMediaById = foodNutritionMapper
                .selectList(new QueryWrapper<FoodNutrition>().in("id", ids))
                .stream()
                .filter(row -> row.getSourceMediaId() != null)
                .collect(Collectors.toMap(FoodNutrition::getId, FoodNutrition::getSourceMediaId, (a, b) -> a));
        for (ItemInput item : items) {
            Long libraryFoodId = item.getLibraryFoodId();
            if (libraryFoodId == null) {
                continue;
            }
            Long sourceMediaId = sourceMediaById.get(libraryFoodId);
            if (sourceMediaId != null && !sourceMediaId.equals(mediaId)) {
                item.setLibraryFoodId(null);
                item.setLibraryStatus("UNRESOLVED");
            }
        }
    }

    /** 将 mealId 下所有明细统一标记为逻辑删除，保留审计（删除时间/操作人）。 */
    private void markItemsDeleted(Long mealId) {
        LocalDateTime now = LocalDateTime.now();
        long operator = RequestContext.operatorIdOrZero();
        mealFoodItemMapper.update(
                null,
                new LambdaUpdateWrapper<MealFoodItem>()
                        .eq(MealFoodItem::getMealId, mealId)
                        .set(MealFoodItem::getIsDeleted, 1)
                        .set(MealFoodItem::getDeletedAt, now)
                        .set(MealFoodItem::getDeletedBy, operator));
    }

    /**
     * 读取识别快照。只有「token 有值 + 快照存在 + payload userId 与当前用户一致 + mediaId 与本次照片一致」
     * 才认账；其余情况一律返回 null，由调用方按「无 token」降级，不拒绝保存。
     * <p>payload 里的 userId 必须二次校验：Redis key 里带 userId 只是定位，不能当作鉴权。
     */
    private MealAnalysisSnapshotStore.Snapshot loadSnapshot(Long userId, String token, Long mediaId) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        MealAnalysisSnapshotStore.Snapshot snapshot = analysisSnapshots.get(userId, token).orElse(null);
        if (snapshot == null) {
            return null;
        }
        if (!userId.equals(snapshot.getUserId())) {
            log.warn("识别快照归属不符，按无 token 处理：userId={} token={}",
                    userId, MealAnalysisSnapshotStore.maskToken(token));
            return null;
        }
        if (mediaId == null || !mediaId.equals(snapshot.getMediaId())) {
            log.warn("识别快照媒体不符，按无 token 处理：userId={} mediaId={} snapshotMediaId={} token={}",
                    userId, mediaId, snapshot.getMediaId(), MealAnalysisSnapshotStore.maskToken(token));
            return null;
        }
        return snapshot;
    }

    /** 快照 issuedAt（epoch millis）转服务端本地时间；快照缺时间时返回 null，不猜值。 */
    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        return epochMillis == null
                ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
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
    private MealVos.MealDetail toDetail(
            MealRecord r, Long userId, List<MealFoodItem> items, Map<String, BigDecimal> glucoseByNode) {
        String mediaUrl = null;
        if (r.getMediaId() != null) {
            MediaFile media =
                    mediaFileService.findOwnedMedia(r.getMediaId(), userId, MediaBizType.MEAL);
            mediaUrl =
                    media == null
                            ? null
                            : com.threadfit.glucopilot.meal.service.MealPhotoService.path(
                                    media.getId());
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
                .recordedAt(r.getRecordedAt())
                .analysisStatus(analysisStatusOf(r.getAnalysisStatus()))
                .analysis(toAnalysis(r, items))
                .glucoseContext(toGlucoseContext(r.getNodeCode(), glucoseByNode))
                .items(itemDetails)
                .build();
    }

    /** 分析列 → VO：只有整餐档位存在时才认为有分析结论（历史记录为 null）。 */
    private static MealVos.Analysis toAnalysis(MealRecord r, List<MealFoodItem> items) {
        if (r.getAnalysisTier() == null) {
            return null;
        }
        List<MealVos.AnalysisItem> analysisItems =
                items.stream()
                        .filter(i -> i.getAdviceTier() != null)
                        .map(
                                i ->
                                        MealVos.AnalysisItem.builder()
                                                .foodName(i.getFoodName())
                                                .tier(i.getAdviceTier())
                                                .ruleCode(i.getAdviceReasonCode())
                                                .advice(i.getAdviceText())
                                                .build())
                        .toList();
        return MealVos.Analysis.builder()
                .tier(r.getAnalysisTier())
                .tierLabel(MealAnalysisTexts.labelOf(r.getAnalysisTier()))
                .summary(r.getAnalysisSummary())
                .ruleVersion(r.getAnalysisRuleVersion())
                .items(analysisItems)
                .build();
    }

    /** analysis_status 数值 → 契约字符串；null 表示历史记录未分析。 */
    private static String analysisStatusOf(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> MealAnalysisGenerator.STATUS_PENDING;
            case 1 -> MealAnalysisGenerator.STATUS_READY;
            case 2 -> MealAnalysisGenerator.STATUS_FAILED;
            default -> null;
        };
    }

    /** 只读血糖上下文：只取该餐点对应的 before/ 与 after/ 两个节点，任一侧缺失则该侧为 null。 */
    private static MealVos.GlucoseContext toGlucoseContext(
            String nodeCode, Map<String, BigDecimal> glucoseByNode) {
        if (nodeCode == null) {
            return null;
        }
        BigDecimal before = glucoseByNode.get("before_" + nodeCode);
        BigDecimal after = glucoseByNode.get("after_" + nodeCode);
        if (before == null && after == null) {
            return null;
        }
        return MealVos.GlucoseContext.builder()
                .before(before)
                .after(after)
                .delta(before != null && after != null ? after.subtract(before) : null)
                .build();
    }

    /** 组装单条明细 VO（id 导出为字符串以兼容前端 id 类型）。 */
    private MealVos.MealItemDetail toItemDetail(MealFoodItem i) {
        return MealVos.MealItemDetail.builder()
                .foodId(i.getFoodId() == null ? null : String.valueOf(i.getFoodId()))
                .libraryFoodId(i.getLibraryFoodId() == null ? null : String.valueOf(i.getLibraryFoodId()))
                .foodName(i.getFoodName())
                .weightG(i.getWeightG())
                .carbsG(i.getCarbsG())
                .caloriesKcal(i.getCaloriesKcal())
                .categoryCode(i.getCategoryCode())
                .giValue(i.getGiValue())
                .giSource(i.getGiSource())
                .nutritionSource(i.getNutritionSource())
                .confidence(i.getConfidence())
                .userCorrected(i.getUserCorrected() != null && i.getUserCorrected() == 1)
                .portionRatio(i.getPortionRatio())
                .adviceTier(i.getAdviceTier())
                .adviceReasonCode(i.getAdviceReasonCode())
                .adviceText(i.getAdviceText())
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
