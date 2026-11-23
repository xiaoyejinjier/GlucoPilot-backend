package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
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
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.vo.MealVos;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MealRecordServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 15);

    @Mock
    private MealRecordMapper mealRecordMapper;
    @Mock
    private MealFoodItemMapper mealFoodItemMapper;
    @Mock
    private MediaFileService mediaFileService;
    @Mock
    private FoodService foodService;
    @Mock
    private MealNutritionResolver nutritionResolver;
    @Mock
    private FoodNutritionMapper foodNutritionMapper;
    @Mock
    private MealAnalysisSnapshotStore analysisSnapshots;
    @Mock
    private MealAnalysisGenerator analysisGenerator;
    @Mock
    private GlucoseRecordService glucoseRecordService;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private MealRecordServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器，Lambda 包装器解析实体列需要手动注册 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MealRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MealFoodItem.class);
    }

    @Test
    void rejectsNonMealNode() {
        MealDtos.SaveRequest request = saveRequest("before_breakfast", null, null, null);

        BizException error = assertThrows(BizException.class, () -> service.save(USER_ID, request));

        assertEquals(ErrorCode.BIZ_REJECTED, error.getErrorCode());
    }

    @Test
    void rejectsInvalidHungerLevel() {
        MealDtos.SaveRequest request = saveRequest("breakfast", null, null, null);
        request.setHungerLevel(9);

        BizException error = assertThrows(BizException.class, () -> service.save(USER_ID, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
    }

    @Test
    void rejectsMediaNotOwnedByUser() {
        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, null);
        when(mediaFileService.findOwnedMedia(eq(200L), eq(USER_ID), any(MediaBizType.class)))
                .thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.save(USER_ID, request));

        assertEquals(ErrorCode.BIZ_REJECTED, error.getErrorCode());
    }

    @Test
    void rejectsUnknownFoodId() {
        MealDtos.Item item = new MealDtos.Item();
        item.setFoodId(100L);
        item.setFoodName("米饭");
        MealDtos.SaveRequest request = saveRequest("breakfast", null, null, List.of(item));
        when(foodService.listAvailableByIds(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> service.save(USER_ID, request));

        assertEquals(ErrorCode.BIZ_REJECTED, error.getErrorCode());
        // 存在性校验必须先于归一化，否则"食物不存在"会被空归一化结果放行
        verify(nutritionResolver, never()).resolve(any(), eq(false));
    }

    @Test
    void insertsWhenAbsent() {
        when(mealRecordMapper.selectOne(any())).thenReturn(null);
        when(mealRecordMapper.insert(any(MealRecord.class))).thenAnswer(inv -> {
            MealRecord row = inv.getArgument(0);
            row.setId(99L);
            return 1;
        });

        MealVos.SaveResult result = service.save(USER_ID, saveRequest("breakfast", null, "6.0", null));

        assertTrue(result.getCreated());
        assertEquals("99", result.getId());
        ArgumentCaptor<MealRecord> captor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals("breakfast", captor.getValue().getNodeCode());
        assertEquals(new BigDecimal("6.0"), captor.getValue().getCarbsG());
        // 新建记录进入待分析态，等待保存后的分析器回填
        assertEquals(0, captor.getValue().getAnalysisStatus());
    }

    @Test
    void updatesWhenPresent() {
        MealRecord existing = record(7L, "lunch");
        when(mealRecordMapper.selectOne(any())).thenReturn(existing);

        MealVos.SaveResult result = service.save(USER_ID, saveRequest("lunch", null, "8.5", null));

        assertFalse(result.getCreated());
        assertEquals("7", result.getId());
        assertEquals(new BigDecimal("8.5"), existing.getCarbsG());
        verify(mealRecordMapper).updateById(existing);
    }

    @Test
    void removeOfAnotherUserIsNotFound() {
        MealRecord other = record(7L, "lunch");
        other.setUserId(2L);
        when(mealRecordMapper.selectById(7L)).thenReturn(other);

        BizException error = assertThrows(BizException.class, () -> service.remove(USER_ID, 7L));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void listDayReturnsMeals() {
        MealRecord row = record(1L, "breakfast");
        row.setMediaId(88L);
        MediaFile photo = new MediaFile(); photo.setId(88L); photo.setFileUrl("http://storage.invalid/private/expired.jpg");
        when(mediaFileService.findOwnedMedia(88L, USER_ID, MediaBizType.MEAL)).thenReturn(photo);
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(row));
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(item(1L, "鸡蛋")));
        when(glucoseRecordService.listRange(USER_ID, DATE, DATE)).thenReturn(List.of());

        MealVos.DayMeals result = service.listDay(USER_ID, DATE);

        assertEquals(DATE, result.getRecordDate());
        assertEquals(1, result.getMeals().size());
        MealVos.MealDetail meal = result.getMeals().getFirst();
        assertEquals("1", meal.getId());
        assertEquals("breakfast", meal.getNodeCode());
        assertEquals("88",meal.getMediaId());
        assertEquals("/api/v1/meal-records/images/88",meal.getMediaUrl());
        assertEquals(1, meal.getItems().size());
        assertEquals("鸡蛋", meal.getItems().getFirst().getFoodName());
    }

    // ---------- 阶段 2b-3a：保存链路归一化与快照落库 ----------

    @Test
    void saveNormalizesItemsInSaveMode() {
        stubCreate();
        ItemInput resolved = input("米饭");
        resolved.setCategoryCode(1);
        resolved.setGiValue(new BigDecimal("83.2"));
        resolved.setNutritionSource("LIBRARY");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(resolved));

        service.save(USER_ID, saveRequest("breakfast", null, null, List.of(clientItem("米饭"))));

        // 保存路径必须走 clientValuesFirst=false，绝不能复用 preview 的 true
        verify(nutritionResolver).resolve(any(), eq(false));
        verify(nutritionResolver, never()).resolve(any(), eq(true));
        MealFoodItem saved = captureSingleItem();
        assertEquals(1, saved.getCategoryCode());
        assertEquals(new BigDecimal("83.2"), saved.getGiValue());
        assertEquals("LIBRARY", saved.getGiSource());
        assertEquals("LIBRARY", saved.getNutritionSource());
        // 客户端没给 libraryFoodId，不该触发草稿归属查询
        verify(foodNutritionMapper, never()).selectList(any());
    }

    @Test
    void keepsLibraryFoodIdWhenDraftHasNoSourceMedia() {
        stubCreate();
        ItemInput resolved = input("鸡蛋");
        resolved.setLibraryFoodId(555L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(resolved));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(555L, null)));

        service.save(USER_ID, saveRequest("breakfast", null, null, List.of(clientItem("鸡蛋"))));

        assertEquals(555L, captureSingleItem().getLibraryFoodId());
    }

    @Test
    void detachesLibraryFoodIdOwnedByAnotherMeal() {
        stubCreate();
        ItemInput resolved = input("鸡蛋");
        resolved.setLibraryFoodId(555L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(resolved));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(555L, 888L)));

        service.save(USER_ID, saveRequest("breakfast", null, null, List.of(clientItem("鸡蛋"))));

        assertNull(captureSingleItem().getLibraryFoodId());
    }

    @Test
    void convertsUserCorrectedToFlag() {
        stubCreate();
        ItemInput corrected = input("米饭");
        corrected.setUserCorrected(true);
        ItemInput untouched = input("青菜");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(corrected, untouched));

        service.save(
                USER_ID,
                saveRequest("breakfast", null, null, List.of(clientItem("米饭"), clientItem("青菜"))));

        ArgumentCaptor<MealFoodItem> captor = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<MealFoodItem> saved = captor.getAllValues();
        assertEquals(1, saved.get(0).getUserCorrected());
        assertEquals(0, saved.get(1).getUserCorrected());
    }

    @Test
    void legacyItemWithoutNewFieldsDoesNotThrow() {
        stubCreate();
        ItemInput legacy = new ItemInput();
        legacy.setFoodName("手输食物");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(legacy));

        service.save(USER_ID, saveRequest("breakfast", null, null, List.of(clientItem("手输食物"))));

        MealFoodItem saved = captureSingleItem();
        assertNull(saved.getCategoryCode());
        assertNull(saved.getGiValue());
        assertEquals("NONE", saved.getGiSource());
        assertNull(saved.getNutritionSource());
        assertNull(saved.getConfidence());
        assertEquals(0, saved.getUserCorrected());
        verify(foodNutritionMapper, never()).selectList(any());
    }

    @Test
    void replaceItemsOnExistingMealWritesSnapshot() {
        MealRecord existing = record(99L, "lunch");
        when(mealRecordMapper.selectOne(any())).thenReturn(existing);

        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(1L);
        rice.setCategoryCode(1);
        rice.setGiValue(new BigDecimal("83.2"));
        rice.setNutritionSource("LIBRARY");
        ItemInput greens = input("青菜");
        greens.setLibraryFoodId(2L);
        greens.setCategoryCode(3);
        greens.setGiValue(new BigDecimal("15.0"));
        greens.setNutritionSource("LIBRARY");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice, greens));
        when(foodNutritionMapper.selectList(any()))
                .thenReturn(List.of(draft(1L, null), draft(2L, null)));

        service.save(
                USER_ID,
                saveRequest("lunch", null, null, List.of(clientItem("米饭"), clientItem("青菜"))));

        // 已存在记录必须走覆盖更新，且明细是「先整体逻辑删除、再逐条插入」
        verify(mealRecordMapper).updateById(existing);
        verify(mealRecordMapper, never()).insert(any(MealRecord.class));
        verify(mealFoodItemMapper).update(org.mockito.ArgumentMatchers.isNull(), any());

        ArgumentCaptor<MealFoodItem> captor = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<MealFoodItem> saved = captor.getAllValues();
        assertEquals(99L, saved.get(0).getMealId());
        assertEquals(0, saved.get(0).getSortNo());
        assertEquals(1L, saved.get(0).getLibraryFoodId());
        assertEquals(1, saved.get(0).getCategoryCode());
        assertEquals(new BigDecimal("83.2"), saved.get(0).getGiValue());
        assertEquals("LIBRARY", saved.get(0).getGiSource());
        assertEquals("LIBRARY", saved.get(0).getNutritionSource());
        assertNull(saved.get(0).getConfidence());
        assertEquals(1, saved.get(1).getSortNo());
        assertEquals(2L, saved.get(1).getLibraryFoodId());
        assertEquals(3, saved.get(1).getCategoryCode());
        assertEquals("LIBRARY", saved.get(1).getGiSource());
    }

    // ---------- 阶段 2b-3b：识别快照 + 低置信拦截 ----------

    @Test
    void snapshotLowConfidenceWithoutConfirmIsRejected() {
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(500L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(500L, null)));
        // 快照 id 是字符串 "500"，保存侧是 500L —— 匹配必须成功，否则拦截会静默失效
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshot(200L, USER_ID, snapItem("500", "米饭", 40, "UNRESOLVED"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        BizException error = assertThrows(BizException.class, () -> service.save(USER_ID, request));

        assertEquals(ErrorCode.BIZ_REJECTED, error.getErrorCode());
        assertTrue(error.getMessage().contains("#1 米饭"));
        // 拦截必须发生在任何写库之前
        verify(mealRecordMapper, never()).insert(any(MealRecord.class));
        verify(mealRecordMapper, never()).updateById(any(MealRecord.class));
        verify(mealFoodItemMapper, never()).insert(any(MealFoodItem.class));
        verify(mealFoodItemMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void snapshotLowConfidenceWithConfirmWritesSnapshotColumns() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(500L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(500L, null)));
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshot(200L, USER_ID, snapItem("500", "米饭", 40, "PENDING_REVIEW"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");
        request.setConfirmLowConfidence(true);

        service.save(USER_ID, request);

        ArgumentCaptor<MealRecord> mealCaptor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(mealCaptor.capture());
        assertEquals("tok-1", mealCaptor.getValue().getAnalysisToken());
        MealFoodItem saved = captureSingleItem();
        // V90 新增列必须真正落库，不能因为实体缺字段被静默跳过
        assertEquals("PENDING_REVIEW", saved.getLibraryStatus());
    }

    @Test
    void insertWritesRecognitionMetaFromSnapshot() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(input("米饭")));
        long issuedAt = 1_760_000_000_123L;
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshotAt(200L, USER_ID, issuedAt, snapItem(null, "米饭", 95, "AVAILABLE"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");
        request.setConfirmLowConfidence(true);

        service.save(USER_ID, request);

        ArgumentCaptor<MealRecord> captor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(captor.capture());
        MealRecord saved = captor.getValue();
        assertEquals("qwen3-vl-plus", saved.getRecognitionModel());
        assertEquals(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(issuedAt), java.time.ZoneId.systemDefault()),
                saved.getRecognitionAt());
        // 确认时间取服务端当前时间，不来自客户端
        assertTrue(saved.getConfirmedAt() != null);
    }

    @Test
    void noSnapshotLeavesRecognitionMetaNull() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(input("米饭")));
        when(analysisSnapshots.get(USER_ID, "tok-1")).thenReturn(java.util.Optional.empty());

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        service.save(USER_ID, request);

        ArgumentCaptor<MealRecord> captor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(captor.capture());
        assertNull(captor.getValue().getRecognitionModel());
        assertNull(captor.getValue().getRecognitionAt());
        // 手输/旧客户端没有识别，但保存即确认，确认时间仍要写
        assertTrue(captor.getValue().getConfirmedAt() != null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatePathExplicitlyResetsRecognitionMeta() {
        MealRecord existing = record(7L, "breakfast");
        existing.setRecognitionModel("old-model");
        existing.setRecognitionAt(java.time.LocalDateTime.of(2026, 1, 1, 8, 0));
        when(mealRecordMapper.selectOne(any())).thenReturn(existing);
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(input("米饭")));
        when(analysisSnapshots.get(USER_ID, "tok-2")).thenReturn(java.util.Optional.empty());

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-2");

        service.save(USER_ID, request);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mealRecordMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapper.capture());
        // 无快照覆盖保存必须把上次识别信息显式清空，updateById 会跳 null 导致残留
        assertTrue(wrapper.getValue().getSqlSet().contains("recognition_model"));
        assertTrue(wrapper.getValue().getSqlSet().contains("recognition_at"));
        assertTrue(wrapper.getValue().getSqlSet().contains("confirmed_at"));
    }

    @Test
    void snapshotAllAvailableWithoutConfirmSaves() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(500L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(500L, null)));
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshot(200L, USER_ID, snapItem("500", "米饭", 95, "AVAILABLE"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        service.save(USER_ID, request);

        assertEquals("AVAILABLE", captureSingleItem().getLibraryStatus());
    }

    @Test
    void expiredSnapshotSkipsInterceptionAndClearsToken() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(500L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(500L, null)));
        // 快照已过期
        when(analysisSnapshots.get(USER_ID, "tok-1")).thenReturn(java.util.Optional.empty());

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        service.save(USER_ID, request);

        ArgumentCaptor<MealRecord> captor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(captor.capture());
        assertNull(captor.getValue().getAnalysisToken());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatePathExplicitlyClearsStaleToken() {
        // updateById 忽略 null 字段，旧令牌必须显式 UPDATE 清空，否则降级后仍残留脏数据
        MealRecord existing = record(7L, "breakfast");
        existing.setAnalysisToken("old-token");
        when(mealRecordMapper.selectOne(any())).thenReturn(existing);
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(input("米饭")));
        when(analysisSnapshots.get(USER_ID, "tok-2")).thenReturn(java.util.Optional.empty());

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-2");

        service.save(USER_ID, request);

        verify(mealRecordMapper).updateById(existing);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mealRecordMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_token"));
        // 明细已重建，旧分析结论必须一并重置，否则会回显过期结论
        assertTrue(wrapper.getValue().getSqlSet().contains("analysis_status"));
    }

    @Test
    void mediaMismatchSkipsInterceptionAndClearsToken() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(500L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(500L, null)));
        // 快照属于另一张照片（识别用 A、保存换 B）
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshot(999L, USER_ID, snapItem("500", "米饭", 40, "UNRESOLVED"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        service.save(USER_ID, request);

        ArgumentCaptor<MealRecord> captor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(captor.capture());
        assertNull(captor.getValue().getAnalysisToken());
    }

    @Test
    void userMismatchSkipsInterceptionAndClearsToken() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput rice = input("米饭");
        rice.setLibraryFoodId(500L);
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(rice));
        when(foodNutritionMapper.selectList(any())).thenReturn(List.of(draft(500L, null)));
        // key 里是当前用户，但 payload 的 userId 是别人：不能只依赖 key 做鉴权
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshot(200L, 2L, snapItem("500", "米饭", 40, "UNRESOLVED"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        service.save(USER_ID, request);

        ArgumentCaptor<MealRecord> captor = ArgumentCaptor.forClass(MealRecord.class);
        verify(mealRecordMapper).insert(captor.capture());
        assertNull(captor.getValue().getAnalysisToken());
    }

    @Test
    void manualEntryMatchingSnapshotNameIsNotIntercepted() {
        stubCreate();
        when(mediaFileService.findOwnedMedia(200L, USER_ID, MediaBizType.MEAL)).thenReturn(photo(200L));
        ItemInput manual = input("米饭");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(manual));
        when(analysisSnapshots.get(USER_ID, "tok-1"))
                .thenReturn(java.util.Optional.of(snapshot(200L, USER_ID, snapItem(null, "米饭", 40, "UNRESOLVED"))));

        MealDtos.SaveRequest request = saveRequest("breakfast", 200L, null, List.of(clientItem("米饭")));
        request.setAnalysisToken("tok-1");

        service.save(USER_ID, request);

        verify(mealFoodItemMapper).insert(any(MealFoodItem.class));
    }

    @Test
    void legacyWithoutTokenSkipsInterception() {
        stubCreate();
        ItemInput unresolved = input("米饭");
        unresolved.setFoodId(700L);
        unresolved.setLibraryStatus("UNRESOLVED");
        when(nutritionResolver.resolve(any(), eq(false))).thenReturn(List.of(unresolved));

        service.save(USER_ID, saveRequest("breakfast", null, null, List.of(clientItem("米饭"))));

        MealFoodItem saved = captureSingleItem();
        assertEquals("UNRESOLVED", saved.getLibraryStatus());
    }

    // ---------- 阶段 2b-3c：保存后生成分析 + 查询回显 ----------

    @Test
    void saveReturnsGeneratedAnalysis() {
        stubCreate();
        when(analysisGenerator.generate(USER_ID, 99L, null))
                .thenReturn(new MealAnalysisGenerator.Result(
                        MealAnalysisGenerator.STATUS_READY, analysis("HIGH", "v2"), true));

        MealVos.SaveResult result = service.save(USER_ID, saveRequest("breakfast", null, null, null));

        assertEquals(MealAnalysisGenerator.STATUS_READY, result.getAnalysisStatus());
        assertTrue(result.getRuleVersionChanged());
        assertEquals("HIGH", result.getAnalysis().getTier());
        assertEquals("v2", result.getAnalysis().getRuleVersion());
    }

    @Test
    void analysisFailureKeepsSaveSucceeded() {
        stubCreate();
        when(analysisGenerator.generate(USER_ID, 99L, null))
                .thenReturn(new MealAnalysisGenerator.Result(
                        MealAnalysisGenerator.STATUS_FAILED, null, false));

        MealVos.SaveResult result = service.save(USER_ID, saveRequest("breakfast", null, null, null));

        // 分析失败只影响 analysisStatus，主记录照常保存成功
        assertTrue(result.getCreated());
        assertEquals("99", result.getId());
        assertEquals(MealAnalysisGenerator.STATUS_FAILED, result.getAnalysisStatus());
        assertNull(result.getAnalysis());
    }

    @Test
    void saveWithoutGeneratorResultReportsPending() {
        stubCreate();

        MealVos.SaveResult result = service.save(USER_ID, saveRequest("breakfast", null, null, null));

        assertEquals(MealAnalysisGenerator.STATUS_PENDING, result.getAnalysisStatus());
        assertNull(result.getAnalysis());
        assertFalse(result.getRuleVersionChanged());
    }

    @Test
    void savePassesPreviousRuleVersionToGenerator() {
        MealRecord existing = record(7L, "lunch");
        existing.setAnalysisRuleVersion("v1");
        when(mealRecordMapper.selectOne(any())).thenReturn(existing);

        service.save(USER_ID, saveRequest("lunch", null, null, null));

        // 覆盖保存前必须把旧版本带出去，否则 ruleVersionChanged 永远是 false
        verify(analysisGenerator).generate(USER_ID, 7L, "v1");
    }

    @Test
    void listDayEchoesAnalysisAndItemAdvice() {
        MealRecord row = record(1L, "breakfast");
        row.setAnalysisStatus(1);
        row.setAnalysisTier("HIGH");
        row.setAnalysisSummary("本餐整体升糖负担较高，可减少高升糖主食并增加蔬菜比例。");
        row.setAnalysisRuleVersion("v2");
        MealFoodItem rice = item(1L, "米饭");
        rice.setLibraryFoodId(500L);
        rice.setCategoryCode(1);
        rice.setGiValue(new BigDecimal("83.2"));
        rice.setGiSource("LIBRARY");
        rice.setNutritionSource("LIBRARY");
        rice.setConfidence(95);
        rice.setUserCorrected(1);
        rice.setPortionRatio(new BigDecimal("1.50"));
        rice.setAdviceTier("HIGH");
        rice.setAdviceReasonCode("GI_HIGH_70");
        rice.setAdviceText("该类食物升糖负担较高，建议减少份量或替换为低升糖主食。");
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(row));
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(rice));
        when(glucoseRecordService.listRange(USER_ID, DATE, DATE)).thenReturn(List.of());

        MealVos.MealDetail meal = service.listDay(USER_ID, DATE).getMeals().getFirst();

        assertEquals("READY", meal.getAnalysisStatus());
        assertEquals("HIGH", meal.getAnalysis().getTier());
        assertEquals("升糖负担较高", meal.getAnalysis().getTierLabel());
        assertEquals("v2", meal.getAnalysis().getRuleVersion());
        assertEquals(1, meal.getAnalysis().getItems().size());
        MealVos.AnalysisItem advice = meal.getAnalysis().getItems().getFirst();
        assertEquals("米饭", advice.getFoodName());
        assertEquals("GI_HIGH_70", advice.getRuleCode());
        MealVos.MealItemDetail detail = meal.getItems().getFirst();
        assertEquals("500", detail.getLibraryFoodId());
        assertEquals(1, detail.getCategoryCode());
        assertEquals(new BigDecimal("83.2"), detail.getGiValue());
        assertEquals("LIBRARY", detail.getGiSource());
        assertEquals("LIBRARY", detail.getNutritionSource());
        assertEquals(95, detail.getConfidence());
        assertTrue(detail.getUserCorrected());
        assertEquals(new BigDecimal("1.50"), detail.getPortionRatio());
        assertEquals("HIGH", detail.getAdviceTier());
        assertEquals("GI_HIGH_70", detail.getAdviceReasonCode());
        assertNull(meal.getGlucoseContext());
    }

    @Test
    void listDayBuildsGlucoseContextFromBeforeAndAfterNodes() {
        MealRecord row = record(1L, "breakfast");
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(row));
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of());
        when(glucoseRecordService.listRange(USER_ID, DATE, DATE))
                .thenReturn(List.of(glucose("before_breakfast", "6.0"), glucose("after_breakfast", "8.5")));

        MealVos.GlucoseContext context =
                service.listDay(USER_ID, DATE).getMeals().getFirst().getGlucoseContext();

        assertEquals(new BigDecimal("6.0"), context.getBefore());
        assertEquals(new BigDecimal("8.5"), context.getAfter());
        assertEquals(new BigDecimal("2.5"), context.getDelta());
    }

    @Test
    void listDayMissingGlucoseSideKeepsDeltaNull() {
        MealRecord row = record(1L, "lunch");
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(row));
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of());
        // 只录了餐后值：不得凭空造出 delta，也不得拿其他时段的值顶替
        when(glucoseRecordService.listRange(USER_ID, DATE, DATE))
                .thenReturn(List.of(glucose("after_lunch", "9.1"), glucose("before_breakfast", "5.5")));

        MealVos.GlucoseContext context =
                service.listDay(USER_ID, DATE).getMeals().getFirst().getGlucoseContext();

        assertNull(context.getBefore());
        assertEquals(new BigDecimal("9.1"), context.getAfter());
        assertNull(context.getDelta());
    }

    @Test
    void listDayHistoricalRecordHasNoAnalysis() {
        MealRecord row = record(2L, "dinner");
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(row));
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of());
        when(glucoseRecordService.listRange(USER_ID, DATE, DATE)).thenReturn(List.of());

        MealVos.MealDetail meal = service.listDay(USER_ID, DATE).getMeals().getFirst();

        assertNull(meal.getAnalysisStatus());
        assertNull(meal.getAnalysis());
        assertNull(meal.getGlucoseContext());
    }

    private static MealVos.Analysis analysis(String tier, String ruleVersion) {
        return MealVos.Analysis.builder()
                .tier(tier)
                .tierLabel("升糖负担较高")
                .summary("摘要")
                .ruleVersion(ruleVersion)
                .items(List.of(MealVos.AnalysisItem.builder()
                        .foodName("米饭")
                        .tier(tier)
                        .ruleCode("GI_HIGH_70")
                        .advice("建议")
                        .build()))
                .build();
    }

    private static GlucoseRecord glucose(String nodeCode, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setUserId(USER_ID);
        row.setRecordDate(DATE);
        row.setNodeCode(nodeCode);
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }

    private static MealAnalysisSnapshotStore.Snapshot snapshot(
            Long mediaId, Long userId, MealAnalysisSnapshotStore.SnapshotItem... items) {
        return snapshotAt(mediaId, userId, System.currentTimeMillis(), items);
    }

    private static MealAnalysisSnapshotStore.Snapshot snapshotAt(
            Long mediaId, Long userId, Long issuedAt, MealAnalysisSnapshotStore.SnapshotItem... items) {
        return new MealAnalysisSnapshotStore.Snapshot(
                userId, mediaId, "breakfast", issuedAt, "qwen3-vl-plus", List.of(items));
    }

    private static MealAnalysisSnapshotStore.SnapshotItem snapItem(
            String libraryFoodId, String foodName, Integer confidence, String libraryStatus) {
        return new MealAnalysisSnapshotStore.SnapshotItem(
                foodName, null, libraryFoodId, null, null, null, libraryStatus, confidence);
    }

    private static MediaFile photo(Long id) {
        MediaFile file = new MediaFile();
        file.setId(id);
        return file;
    }

    /** 新建主记录并回填 id，测试内复用。 */
    private void stubCreate() {
        when(mealRecordMapper.selectOne(any())).thenReturn(null);
        when(mealRecordMapper.insert(any(MealRecord.class))).thenAnswer(inv -> {
            MealRecord row = inv.getArgument(0);
            row.setId(99L);
            return 1;
        });
    }

    private MealFoodItem captureSingleItem() {
        ArgumentCaptor<MealFoodItem> captor = ArgumentCaptor.forClass(MealFoodItem.class);
        verify(mealFoodItemMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static ItemInput input(String foodName) {
        ItemInput item = new ItemInput();
        item.setFoodName(foodName);
        return item;
    }

    private static MealDtos.Item clientItem(String foodName) {
        MealDtos.Item item = new MealDtos.Item();
        item.setFoodName(foodName);
        return item;
    }

    private static FoodNutrition draft(Long id, Long sourceMediaId) {
        FoodNutrition row = new FoodNutrition();
        row.setId(id);
        row.setSourceMediaId(sourceMediaId);
        return row;
    }

    private static MealDtos.SaveRequest saveRequest(String nodeCode, Long mediaId, String carbsG, List<MealDtos.Item> items) {
        MealDtos.SaveRequest request = new MealDtos.SaveRequest();
        request.setRecordDate(DATE);
        request.setNodeCode(nodeCode);
        request.setMediaId(mediaId);
        request.setCarbsG(carbsG == null ? null : new BigDecimal(carbsG));
        request.setItems(items);
        return request;
    }

    private static MealRecord record(Long id, String nodeCode) {
        MealRecord row = new MealRecord();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setRecordDate(DATE);
        row.setNodeCode(nodeCode);
        return row;
    }

    private static MealFoodItem item(Long mealId, String foodName) {
        MealFoodItem row = new MealFoodItem();
        row.setMealId(mealId);
        row.setFoodName(foodName);
        return row;
    }
}
