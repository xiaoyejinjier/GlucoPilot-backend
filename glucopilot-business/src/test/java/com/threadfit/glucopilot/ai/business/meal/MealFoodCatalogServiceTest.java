package com.threadfit.glucopilot.ai.business.meal;

import com.threadfit.glucopilot.food.entity.FoodCategory;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MealFoodCatalogServiceTest {
    @Mock private FoodNutritionMapper foods;
    @Mock private FoodCategoryMapper categories;
    @InjectMocks private MealFoodCatalogService service;

    @Test void libraryNutritionOverridesModelEstimatesWithoutChangingLibrary() {
        FoodNutrition rice = row(1L, "米饭", 1, "MANUAL");
        when(foods.selectList(any())).thenReturn(List.of(rice));
        var result = service.resolve(input("米饭", "99", "999", "150", 95), "qwen3-vl-plus");
        assertEquals(new BigDecimal("38.9"), result.item().getCarbsG());
        assertEquals(new BigDecimal("174.0"), result.item().getCaloriesKcal());
        assertEquals("1", result.item().getFoodId());
        assertEquals("LIBRARY", result.item().getNutritionSource());
        verify(foods, never()).insertRecognitionDraft(any());
        verify(foods, never()).updateById(any(FoodNutrition.class));
    }

    @Test void unknownFoodIsRegisteredAsUnpublishedEstimate() {
        when(foods.selectList(any())).thenReturn(List.of());
        FoodCategory category = new FoodCategory();
        category.setId(1L); category.setIsEnabled(1);
        when(categories.selectById(1L)).thenReturn(category);
        when(foods.insertRecognitionDraft(any())).thenReturn(1);
        FoodNutrition draft = row(7L, "杂粮饭", 0, "AI_ESTIMATE");
        when(foods.selectOne(any())).thenReturn(draft);
        var result = service.resolve(input("杂粮饭", "25.9", "116", "100", 90), "qwen3-vl-plus");
        assertTrue(result.added());
        assertNull(result.item().getFoodId());
        assertEquals("7", result.item().getLibraryFoodId());
        assertEquals("PENDING_REVIEW", result.item().getLibraryStatus());
        assertEquals("AI_ESTIMATE", result.item().getNutritionSource());
        ArgumentCaptor<FoodNutrition> capture = ArgumentCaptor.forClass(FoodNutrition.class);
        verify(foods).insertRecognitionDraft(capture.capture());
        assertEquals("qwen3-vl-plus", capture.getValue().getSourceModel());
        assertEquals(1L, capture.getValue().getCategoryId());
    }

    @Test void existingDraftIsReusedWithoutOverwritingItsNutrition() {
        when(foods.selectList(any())).thenReturn(List.of(row(7L, "杂粮饭", 0, "AI_ESTIMATE")));
        var result = service.resolve(input("杂粮饭", "99", "999", "100", 90), "qwen3-vl-plus");
        assertFalse(result.added());
        assertEquals(new BigDecimal("25.9"), result.item().getCarbsG());
        verify(foods, never()).insertRecognitionDraft(any());
    }

    @Test void newDraftKeepsTheVerifiedUploadedPhoto() {
        when(foods.selectList(any())).thenReturn(List.of());
        when(foods.insertRecognitionDraft(any())).thenReturn(1);
        FoodNutrition draft = row(7L, "新食品", 0, "AI_ESTIMATE");
        draft.setSourceMediaId(88L);
        when(foods.selectOne(any())).thenReturn(draft);
        service.resolve(input("新食品", "20", "120", "100", 90), "vision", 88L);
        ArgumentCaptor<FoodNutrition> capture = ArgumentCaptor.forClass(FoodNutrition.class);
        verify(foods).insertRecognitionDraft(capture.capture());
        assertEquals(88L, capture.getValue().getSourceMediaId());
        verify(foods, never()).attachRecognitionPhoto(any(), any());
    }

    @Test void oldDraftCanReceiveFirstPhotoButAnExistingPhotoIsNeverReplaced() {
        FoodNutrition draft = row(7L, "杂粮饭", 0, "AI_ESTIMATE");
        draft.setImageUrl("/images/foods/rice.svg");
        when(foods.selectList(any())).thenReturn(List.of(draft));
        when(foods.attachRecognitionPhoto(7L, 88L)).thenReturn(1);
        service.resolve(input("杂粮饭", "99", "999", "100", 90), "vision", 88L);
        service.resolve(input("杂粮饭", "99", "999", "100", 90), "vision", 99L);
        assertEquals(88L, draft.getSourceMediaId());
        assertEquals("/images/foods/rice.svg", draft.getImageUrl());
        assertEquals(new BigDecimal("25.9"), draft.getCarbsPer100g());
        verify(foods).attachRecognitionPhoto(7L, 88L);
        verify(foods, never()).attachRecognitionPhoto(7L, 99L);
    }

    @Test void manualAndReviewedFoodNeverReceiveUserPhoto() {
        for (String source : List.of("MANUAL", "AI_REVIEWED")) {
            FoodNutrition food = row(7L, "米饭", 0, source);
            when(foods.selectList(any())).thenReturn(List.of(food));
            service.resolve(input("米饭", "99", "999", "100", 90), "vision", 88L);
            assertNull(food.getSourceMediaId());
        }
        verify(foods, never()).attachRecognitionPhoto(any(), any());
    }

    @Test void uncertainFoodDoesNotPolluteLibraryOrInventNutrition() {
        var result = service.resolve(input("不确定菜品", "10", "100", "100", 30), "qwen3-vl-plus");
        assertNull(result.item().getCarbsG());
        assertEquals("UNKNOWN", result.item().getNutritionSource());
        verifyNoInteractions(foods, categories);
    }

    @Test void unknownWeightRemainsUnknownEvenWhenFoodMatches() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, "MANUAL")));
        var result = service.resolve(input("米饭", "10", "100", null, 90), "qwen3-vl-plus");
        assertNull(result.item().getWeightG());
        assertNull(result.item().getCarbsG());
        assertEquals(new BigDecimal("25.9"), result.item().getCarbsPer100g());
    }

    @Test void invalidModelNutritionCannotCreateFood() {
        when(foods.selectList(any())).thenReturn(List.of());
        var result = service.resolve(input("新食品", "101", "100", "100", 90), "qwen3-vl-plus");
        assertNull(result.item().getLibraryFoodId());
        verify(foods, never()).insertRecognitionDraft(any());
    }

    @Test void outOfRangeConfidenceDoesNotBecomeTrusted() {
        var result = service.resolve(input("新食品", "20", "120", "100", 999), "qwen3-vl-plus");
        assertEquals(0, result.item().getConfidence());
        assertNull(result.item().getLibraryFoodId());
        verifyNoInteractions(foods, categories);
    }

    private static FoodNutrition row(long id, String name, int shelf, String source) {
        FoodNutrition food = new FoodNutrition();
        food.setId(id); food.setFoodName(name); food.setCategoryCode(1); food.setShelfStatus(shelf);
        food.setNutritionSource(source); food.setCarbsPer100g(new BigDecimal("25.9"));
        food.setCaloriesPer100g(new BigDecimal("116")); return food;
    }
    private static MealAnalysisDtos.ModelFood input(String name, String carbs, String calories, String weight, int confidence) {
        var input = new MealAnalysisDtos.ModelFood();
        input.setName(name); input.setCategoryCode(1); input.setConfidence(confidence);
        input.setCarbsPer100g(new BigDecimal(carbs)); input.setCaloriesPer100g(new BigDecimal(calories));
        input.setWeightG(weight == null ? null : new BigDecimal(weight)); return input;
    }
}
