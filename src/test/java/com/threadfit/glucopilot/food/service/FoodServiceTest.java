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

/**
 * {@link FoodService} 的单元测试。
 *
 * <p>测试不连接真实数据库，而是使用 Mockito 创建两个 Mapper 的模拟对象，
 * 预先规定 Mapper 返回什么数据，再验证 Service 的过滤、转换和异常逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class FoodServiceTest {

    /** 模拟分类 Mapper，避免测试访问真实 food_category 表。 */
    @Mock
    private FoodCategoryMapper foodCategoryMapper;

    /** 模拟食品 Mapper，避免测试访问真实 food_nutrition 表。 */
    @Mock
    private FoodNutritionMapper foodNutritionMapper;

    /** Mockito 会把上面的两个 Mock Mapper 注入被测试的 FoodService。 */
    @InjectMocks
    private FoodService service;

    /** 验证分类实体转换为 DTO 时，Long 类型 ID 会转换为 String。 */
    @Test
    void categoriesAreMappedWithIdAsString() {
        // Arrange：准备一条模拟分类，并规定 Mapper 查询时返回它。
        FoodCategory category = new FoodCategory();
        category.setId(1L);
        category.setCategoryName("主食类");
        category.setSortNo(1);
        when(foodCategoryMapper.selectList(any())).thenReturn(List.of(category));

        // Act：调用真正的 Service 方法。
        List<FoodDtos.FoodCategoryItem> items = service.listFoodCategories();

        // Assert：检查返回数量、ID 类型和分类名称。
        assertEquals(1, items.size());
        assertEquals("1", items.get(0).getId());
        assertEquals("主食类", items.get(0).getCategoryName());
    }

    /** 验证食品详情会根据 categoryId 补充对应的分类名称。 */
    @Test
    void detailResolvesCategoryName() {
        // 模拟食品表返回米饭，分类表返回主食类。
        when(foodNutritionMapper.selectById(1L)).thenReturn(food(1L, "米饭", 1, 1));
        when(foodCategoryMapper.selectList(null)).thenReturn(List.of(category(1L, "主食类")));

        // 调用详情查询。
        FoodDtos.FoodItem detail = service.getFoodDetail(1L);

        // 验证食品字段和补充的分类名称。
        assertEquals("米饭", detail.getFoodName());
        assertEquals("主食类", detail.getCategoryName());
        assertEquals(new BigDecimal("25.9"), detail.getCarbsPer100g());
    }

    /** 验证已下架食品不能通过用户端详情接口读取。 */
    @Test
    void offShelfFoodIsNotFound() {
        // shelfStatus=0 表示下架。
        FoodNutrition offShelf = food(2L, "下架食物", 1, 0);
        when(foodNutritionMapper.selectById(2L)).thenReturn(offShelf);

        // Service 应抛出 BizException，而不是返回下架食品。
        assertThrows(BizException.class, () -> service.getFoodDetail(2L));
    }

    /** 验证分页查询能够保留分页总数，并正确转换食品和分类名称。 */
    @Test
    void pageMapsRecordsAndCategoryNames() {
        // 模拟 MyBatis-Plus selectPage：向传入的 Page 对象放入一条米饭记录。
        when(foodNutritionMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<FoodNutrition> page = inv.getArgument(0);
            page.setRecords(List.of(food(1L, "米饭", 1, 1)));
            page.setTotal(1);
            return page;
        });
        when(foodCategoryMapper.selectList(null)).thenReturn(List.of(category(1L, "主食类")));

        // categoryCode=1、keyword=米，使用 PageQuery 默认分页参数。
        PageResult<FoodDtos.FoodItem> page = service.pageFoods(null, 1, "米", new PageQuery());

        // 验证分页总数以及 DTO 字段映射。
        assertEquals(1, page.getTotal());
        assertEquals("米饭", page.getRecords().get(0).getFoodName());
        assertEquals("主食类", page.getRecords().get(0).getCategoryName());
    }

    /** 创建测试用食品实体，减少多个测试中的重复准备代码。 */
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

    /** 创建测试用分类实体。 */
    private static FoodCategory category(Long id, String name) {
        FoodCategory category = new FoodCategory();
        category.setId(id);
        category.setCategoryName(name);
        return category;
    }
}
