package com.threadfit.glucopilot.meal.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.service.FoodService;
import com.threadfit.glucopilot.meal.dto.MealDtos;
import com.threadfit.glucopilot.meal.entity.MealFoodItem;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.mapper.MealFoodItemMapper;
import com.threadfit.glucopilot.meal.mapper.MealRecordMapper;
import com.threadfit.glucopilot.meal.vo.MealVos;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(mealRecordMapper.selectList(any())).thenReturn(List.of(row));
        when(mealFoodItemMapper.selectList(any())).thenReturn(List.of(item(1L, "鸡蛋")));

        MealVos.DayMeals result = service.listDay(USER_ID, DATE);

        assertEquals(DATE, result.getRecordDate());
        assertEquals(1, result.getMeals().size());
        MealVos.MealDetail meal = result.getMeals().getFirst();
        assertEquals("1", meal.getId());
        assertEquals("breakfast", meal.getNodeCode());
        assertEquals(1, meal.getItems().size());
        assertEquals("鸡蛋", meal.getItems().getFirst().getFoodName());
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
