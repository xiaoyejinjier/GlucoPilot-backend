package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FoodServiceTest {

    @Mock
    private FoodCategoryMapper foodCategoryMapper;
    @Mock
    private FoodNutritionMapper foodNutritionMapper;

    @InjectMocks
    private FoodService service;

    @Test
    void categoriesAreMappedWithIdAsString() {
        FoodCategory category = new FoodCategory();
        category.setId(1L);
        category.setCategoryName("主食类");
        category.setSortNo(1);
        when(foodCategoryMapper.selectList(any())).thenReturn(List.of(category));

        List<FoodDtos.FoodCategoryItem> items = service.listFoodCategories();

        assertEquals(1, items.size());
        assertEquals("1", items.get(0).getId());
        assertEquals("主食类", items.get(0).getCategoryName());
    }

    @Test
    void detailResolvesCategoryName() {
        when(foodNutritionMapper.selectById(1L)).thenReturn(food(1L, "米饭", 1, 1));
        when(foodCategoryMapper.selectList(null)).thenReturn(List.of(category(1L, "主食类")));

        FoodDtos.FoodItem detail = service.getFoodDetail(1L);

        assertEquals("米饭", detail.getFoodName());
        assertEquals("主食类", detail.getCategoryName());
        assertEquals(new BigDecimal("25.9"), detail.getCarbsPer100g());
    }

    @Test
    void offShelfFoodIsNotFound() {
        FoodNutrition offShelf = food(2L, "下架食物", 1, 0);
        when(foodNutritionMapper.selectById(2L)).thenReturn(offShelf);

        assertThrows(BizException.class, () -> service.getFoodDetail(2L));
    }

    @Test
    void pageMapsRecordsAndCategoryNames() {
        when(foodNutritionMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<FoodNutrition> page = inv.getArgument(0);
            page.setRecords(List.of(food(1L, "米饭", 1, 1)));
            page.setTotal(1);
            return page;
        });
        when(foodCategoryMapper.selectList(null)).thenReturn(List.of(category(1L, "主食类")));

        PageResult<FoodDtos.FoodItem> page = service.pageFoods(null, 1, "米", new PageQuery());

        assertEquals(1, page.getTotal());
        assertEquals("米饭", page.getRecords().get(0).getFoodName());
        assertEquals("主食类", page.getRecords().get(0).getCategoryName());
    }

    private static FoodNutrition food(Long id, String name, long categoryId, int shelfStatus) {
        FoodNutrition food = new FoodNutrition();
        food.setId(id);
        food.setFoodName(name);
        food.setCategoryId(categoryId);
        food.setCategoryCode((int) categoryId);
        food.setCarbsPer100g(new BigDecimal("25.9"));
        food.setCaloriesPer100g(new BigDecimal("116.0"));
        food.setShelfStatus(shelfStatus);
        return food;
    }

    private static FoodCategory category(Long id, String name) {
        FoodCategory category = new FoodCategory();
        category.setId(id);
        category.setCategoryName(name);
        return category;
    }
}
