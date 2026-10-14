package com.threadfit.glucopilot.food.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminFoodServiceTest {

    @Mock
    private FoodCategoryMapper foodCategoryMapper;
    @Mock
    private FoodNutritionMapper foodNutritionMapper;

    @InjectMocks
    private AdminFoodService service;

    @Test
    void categoryImageIsSavedAndReturnedForNewCategory() {
        AdminFoodDtos.FoodCategorySaveRequest request = new AdminFoodDtos.FoodCategorySaveRequest();
        request.setName(" 新主食类 ");
        request.setSort(3);
        request.setImage(" /api/v1/foods/images/72 ");
        AtomicReference<FoodCategory> persisted = new AtomicReference<>();
        when(foodCategoryMapper.insert(any(FoodCategory.class))).thenAnswer(invocation -> {
            FoodCategory row = invocation.getArgument(0);
            row.setId(10L);
            persisted.set(row);
            return 1;
        });
        when(foodCategoryMapper.selectById(10L)).thenAnswer(invocation -> persisted.get());

        AdminFoodDtos.FoodCategoryItem result = service.saveCategory(request);

        ArgumentCaptor<FoodCategory> captor = ArgumentCaptor.forClass(FoodCategory.class);
        verify(foodCategoryMapper).insert(captor.capture());
        assertEquals("/api/v1/foods/images/72", captor.getValue().getImageUrl());
        assertEquals("/api/v1/foods/images/72", result.getImage());
        assertEquals("新主食类", result.getName());
    }

    @Test
    void updatingCategoryWithoutImageKeepsOriginalImage() {
        FoodCategory existing = new FoodCategory();
        existing.setId(1L);
        existing.setImageUrl("/api/v1/foods/images/72");
        when(foodCategoryMapper.selectById(1L)).thenReturn(existing);
        AdminFoodDtos.FoodCategorySaveRequest request = new AdminFoodDtos.FoodCategorySaveRequest();
        request.setId(1L);
        request.setName("主食类");
        request.setSort(2);

        assertEquals("/api/v1/foods/images/72", service.saveCategory(request).getImage());
        verify(foodCategoryMapper).updateById(existing);
    }

    @Test
    void categoryImageCanBeClearedToUseDefaultImage() {
        FoodCategory existing = new FoodCategory();
        existing.setId(1L);
        existing.setImageUrl("/api/v1/foods/images/72");
        when(foodCategoryMapper.selectById(1L)).thenReturn(existing);
        AdminFoodDtos.FoodCategorySaveRequest request = new AdminFoodDtos.FoodCategorySaveRequest();
        request.setId(1L);
        request.setName("主食类");
        request.setImage("");

        assertEquals("", service.saveCategory(request).getImage());
        assertEquals("", existing.getImageUrl());
    }

    @Test
    void categoryListIncludesSavedImageAndSupportsLegacyRows() {
        FoodCategory selected = new FoodCategory();
        selected.setId(1L);
        selected.setCategoryName("主食类");
        selected.setImageUrl("/images/foods/oat.svg");
        FoodCategory legacy = new FoodCategory();
        legacy.setId(2L);
        legacy.setCategoryName("肉蛋类");
        when(foodCategoryMapper.selectList(any())).thenReturn(List.of(selected, legacy));

        List<AdminFoodDtos.FoodCategoryItem> result = service.listCategories();

        assertEquals("/images/foods/oat.svg", result.get(0).getImage());
        assertEquals("", result.get(1).getImage());
    }

    @Test
    void refusesSuccessWhenReadBackImageDiffersFromSubmittedImage() {
        FoodCategory existing = new FoodCategory();
        existing.setId(1L);
        existing.setImageUrl("/images/foods/rice.svg");
        FoodCategory unchangedInDatabase = new FoodCategory();
        unchangedInDatabase.setId(1L);
        unchangedInDatabase.setImageUrl("/images/foods/rice.svg");
        when(foodCategoryMapper.selectById(1L)).thenReturn(existing, unchangedInDatabase);
        AdminFoodDtos.FoodCategorySaveRequest request = new AdminFoodDtos.FoodCategorySaveRequest();
        request.setId(1L);
        request.setName("主食类");
        request.setImage("/api/v1/foods/images/72");

        assertThrows(BizException.class, () -> service.saveCategory(request));
    }

    @Test
    void pageFoodsMapsDatabasePageAndCategoryName() {
        FoodNutrition food = new FoodNutrition();
        food.setId(8L);
        food.setFoodName("糙米饭");
        food.setCategoryId(1L);
        food.setCarbsPer100g(new BigDecimal("33.1"));
        food.setCaloriesPer100g(new BigDecimal("160"));
        food.setGiValue(new BigDecimal("50"));
        food.setShelfStatus(1);

        when(foodNutritionMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<FoodNutrition> page = invocation.getArgument(0);
            page.setRecords(List.of(food));
            page.setTotal(21);
            return page;
        });

        FoodCategory category = new FoodCategory();
        category.setId(1L);
        category.setCategoryName("主食类");
        when(foodCategoryMapper.selectList(any())).thenReturn(List.of(category));

        PageQuery query = new PageQuery();
        query.setPageNum(2);
        query.setPageSize(10);
        PageResult<AdminFoodDtos.FoodItem> result =
                service.pageFoods(1L, " 糙米 ", true, query);

        assertEquals(2, result.getPageNum());
        assertEquals(10, result.getPageSize());
        assertEquals(21, result.getTotal());
        assertEquals(3, result.getPages());
        assertEquals("糙米饭", result.getRecords().getFirst().getName());
        assertEquals("主食类", result.getRecords().getFirst().getCategoryName());
        assertTrue(result.getRecords().getFirst().getEnabled());
    }
}
