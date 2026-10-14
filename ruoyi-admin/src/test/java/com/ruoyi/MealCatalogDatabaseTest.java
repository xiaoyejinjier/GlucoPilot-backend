package com.ruoyi;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.threadfit.glucopilot.ai.business.meal.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.MealFoodCatalogService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式启用时连接现有开发库，测试条目随测试事务回滚。 */
@EnabledIfEnvironmentVariable(named = "RUN_MEAL_DB_TEST", matches = "true")
@SpringBootTest(classes = RuoYiApplication.class, properties = {
        "spring.config.import=optional:file:../../.env[.properties]",
        "spring.quartz.auto-startup=false",
        "debug=false",
        "logging.level.org.springframework=WARN"
})
@Transactional
class MealCatalogDatabaseTest {
    @Autowired private MealFoodCatalogService catalog;
    @Autowired private FoodNutritionMapper foods;

    @Test void newFoodIsUnpublishedAndRepeatedRecognitionDoesNotDuplicateOrOverwrite() {
        String name = "__meal_test_" + UUID.randomUUID().toString().replace("-", "");
        var input = new MealAnalysisDtos.ModelFood();
        input.setName(name); input.setCategoryCode(1); input.setConfidence(90);
        input.setWeightG(new BigDecimal("100")); input.setCarbsPer100g(new BigDecimal("20"));
        input.setCaloriesPer100g(new BigDecimal("120"));
        var first = catalog.resolve(input, "qwen3-vl-plus");
        assertNotNull(first.item().getLibraryFoodId());
        assertNull(first.item().getFoodId());
        assertEquals("PENDING_REVIEW", first.item().getLibraryStatus());
        input.setCarbsPer100g(new BigDecimal("99"));
        var second = catalog.resolve(input, "qwen3-vl-plus");
        assertEquals(first.item().getLibraryFoodId(), second.item().getLibraryFoodId());
        assertEquals(new BigDecimal("20.0"), second.item().getCarbsPer100g());
        assertEquals(1, foods.selectCount(new QueryWrapper<FoodNutrition>().eq("food_name", name)));
        var persisted = foods.selectById(Long.valueOf(first.item().getLibraryFoodId()));
        assertEquals(0, persisted.getShelfStatus());
        assertEquals("AI_ESTIMATE", persisted.getNutritionSource());
    }
}
