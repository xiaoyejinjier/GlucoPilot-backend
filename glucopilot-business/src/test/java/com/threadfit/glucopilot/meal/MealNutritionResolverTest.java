package com.threadfit.glucopilot.meal;

import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;
import com.threadfit.glucopilot.food.entity.FoodNutrition;
import com.threadfit.glucopilot.food.mapper.FoodNutritionMapper;
import com.threadfit.glucopilot.meal.service.MealNutritionResolver;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2b-1：归一化只读组件。重点验证
 * ① 取行规则与识别链路一致（同一份 pickBest）；
 * ② preview/save 两条覆盖策略；
 * ③ 批量查询次数不随明细数增长；
 * ④ 零写入。
 */
@ExtendWith(MockitoExtension.class)
class MealNutritionResolverTest {

    @Mock private FoodNutritionMapper foods;
    private MealNutritionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MealNutritionResolver(foods);
    }

    @Test void resolvesByLibraryFoodId() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var out = resolver.resolve(List.of(item("米饭", 1L, null, null, null, null)), true);
        assertEquals(1L, out.get(0).getLibraryFoodId());
        assertEquals(new BigDecimal("83.2"), out.get(0).getGiValue());
        assertEquals(1, out.get(0).getCategoryCode());
        assertEquals("AVAILABLE", out.get(0).getLibraryStatus());
    }

    @Test void resolvesByNormalizedName() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", null)));
        var out = resolver.resolve(List.of(item(" 米饭 ", null, null, null, null, null)), true);
        assertEquals(1L, out.get(0).getLibraryFoodId());
        assertEquals("LIBRARY", out.get(0).getNutritionSource());
    }

    @Test void batchPathPicksTheSameRowAsSingleLookup() {
        var candidates = List.of(
                row(9L, "米饭", 0, 1, "AI_ESTIMATE", null),
                row(3L, "米饭", 1, 1, "MANUAL", null));
        when(foods.selectList(any())).thenReturn(candidates);
        var out = resolver.resolve(List.of(item("米饭", null, 1, null, null, null)), true);
        assertEquals(3L, out.get(0).getLibraryFoodId());
        assertEquals(3L, MealFoodCatalogService.pickBest(candidates, 1).getId());
    }

    @Test void unmatchedKeepsClientValuesAndWritesNothing() {
        when(foods.selectList(any())).thenReturn(List.of());
        var out = resolver.resolve(List.of(item("未知菜", 999L, 2, new BigDecimal("55"), null, null)), true);
        var resolved = out.get(0);
        assertEquals("UNRESOLVED", resolved.getLibraryStatus());
        assertEquals("UNKNOWN", resolved.getNutritionSource());
        assertEquals(2, resolved.getCategoryCode());
        assertEquals(new BigDecimal("55"), resolved.getGiValue());
        assertNull(resolved.getLibraryFoodId());
        verifyNoWrites();
    }

    @Test void previewModeNeverOverwritesClientValues() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var out = resolver.resolve(List.of(item("米饭", null, 4, new BigDecimal("50"), "AI_ESTIMATE", 95)), true);
        var resolved = out.get(0);
        assertEquals(4, resolved.getCategoryCode());
        assertEquals(new BigDecimal("50"), resolved.getGiValue());
        assertEquals("AI_ESTIMATE", resolved.getNutritionSource());
    }

    @Test void saveModeOverwritesLibraryFactsButLeavesConfidenceToCaller() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var out = resolver.resolve(List.of(item("米饭", null, 4, new BigDecimal("50"), "AI_ESTIMATE", 95)), false);
        var resolved = out.get(0);
        assertEquals(1, resolved.getCategoryCode());
        assertEquals(new BigDecimal("83.2"), resolved.getGiValue());
        assertEquals("LIBRARY", resolved.getNutritionSource());
        assertEquals(95, resolved.getConfidence());
    }

    @Test void userCorrectionFlagIsPassedThrough() {
        when(foods.selectList(any())).thenReturn(List.of());
        var source = item("自定义菜", null, 9, null, null, null);
        source.setUserCorrected(Boolean.TRUE);
        assertEquals(Boolean.TRUE, resolver.resolve(List.of(source), true).get(0).getUserCorrected());
    }

    @Test void unpublishedRowBecomesPendingReviewAndNeedsConfirmation() {
        when(foods.selectList(any())).thenReturn(List.of(row(7L, "杂粮饭", 0, 1, "AI_ESTIMATE", null)));
        var resolved = resolver.resolve(List.of(item("杂粮饭", null, 1, null, null, 95)), true).get(0);
        assertEquals("PENDING_REVIEW", resolved.getLibraryStatus());
        assertEquals("AI_ESTIMATE", resolved.getNutritionSource());
        assertTrue(MealFoodCatalogService.needsConfirmation(95, resolved.getLibraryStatus(), 70));
    }

    @Test void publishedRowWinsOverUnpublishedOne() {
        when(foods.selectList(any())).thenReturn(List.of(
                row(5L, "米饭", 0, 1, "AI_ESTIMATE", null),
                row(2L, "米饭", 1, 1, "MANUAL", null)));
        var resolved = resolver.resolve(List.of(item("米饭", null, 1, null, null, 95)), true).get(0);
        assertEquals(2L, resolved.getLibraryFoodId());
        assertEquals("AVAILABLE", resolved.getLibraryStatus());
    }

    @Test void allUnpublishedStillPicksTheCategoryMatchAndStaysPendingReview() {
        when(foods.selectList(any())).thenReturn(List.of(
                row(6L, "米饭", 0, 2, "AI_ESTIMATE", null),
                row(8L, "米饭", 0, 1, "AI_ESTIMATE", null)));
        var resolved = resolver.resolve(List.of(item("米饭", null, 1, null, null, 95)), true).get(0);
        assertEquals(8L, resolved.getLibraryFoodId());
        assertEquals("PENDING_REVIEW", resolved.getLibraryStatus());
    }

    @Test void queryCountStaysAtTwoForAThousandItems() {
        when(foods.selectList(any())).thenReturn(List.of());
        List<ItemInput> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            items.add(item("食物" + i, (long) (i + 1), null, null, null, null));
        }
        resolver.resolve(items, true);
        verify(foods, times(2)).selectList(any());
    }

    @Test void resolverNeverWrites() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        resolver.resolve(List.of(item("米饭", 1L, 1, null, null, null)), false);
        verifyNoWrites();
    }

    // ===== V94：per100gGL 判定输入回填 =====

    @Test void per100gGlComputedFromLibraryCarbsAndGi() {
        // 库命中：GI 83.2 × 每100g碳水 25.9 / 100 = 21.55（HALF_UP 两位）
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var resolved = resolver.resolve(List.of(item("米饭", 1L, null, null, null, null)), true).get(0);
        assertEquals(new BigDecimal("21.55"), resolved.getPer100gGl());
        assertEquals(new BigDecimal("25.9"), resolved.getCarbsPer100g());
        assertEquals(MealSuitabilityDtos.GI_SOURCE_LIBRARY, resolved.getGiSource());
        assertNull(resolved.getGiReliability());
        assertNull(resolved.getMissingFields());
    }

    @Test void clientGiMarksSourceAndReliability() {
        // 客户端 GI=50 与库 GI=83.2 差异 >20 → giSource=CLIENT 且 giReliability=LOW
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var resolved = resolver.resolve(
                List.of(item("米饭", null, null, new BigDecimal("50"), null, null)), true).get(0);
        assertEquals(MealSuitabilityDtos.GI_SOURCE_CLIENT, resolved.getGiSource());
        assertEquals(MealSuitabilityDtos.GI_RELIABILITY_LOW, resolved.getGiReliability());
        // per100gGL 用客户端 GI 计算：50 × 25.9 / 100 = 12.95
        assertEquals(new BigDecimal("12.95"), resolved.getPer100gGl());
    }

    @Test void clientGiWithinDivergenceLimitKeepsHighReliability() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var resolved = resolver.resolve(
                List.of(item("米饭", null, null, new BigDecimal("75"), null, null)), true).get(0);
        assertEquals(MealSuitabilityDtos.GI_SOURCE_CLIENT, resolved.getGiSource());
        assertNull(resolved.getGiReliability());
    }

    @Test void unresolvedItemHasNoPer100gGlAndMarksMissing() {
        // UNRESOLVED：per100gGL 置空并标记缺失，不做 carbsG/weightG 比值兜底
        when(foods.selectList(any())).thenReturn(List.of());
        var source = item("自定义菜", null, null, null, null, null);
        source.setCarbsG(new BigDecimal("50"));
        source.setWeightG(new BigDecimal("200"));
        var resolved = resolver.resolve(List.of(source), true).get(0);
        assertNull(resolved.getPer100gGl());
        assertNull(resolved.getCarbsPer100g());
        assertEquals(List.of("食品库未匹配", "GI", "每100g碳水"), resolved.getMissingFields());
    }

    @Test void missingCarbsPer100gMarksOnlyThatField() {
        // 库命中但缺每100g碳水（GI 由客户端保留）：只标记碳水密度缺失
        FoodNutrition noCarbs = row(2L, "汤", 1, 1, "MANUAL", null);
        noCarbs.setCarbsPer100g(null);
        when(foods.selectList(any())).thenReturn(List.of(noCarbs));
        var resolved = resolver.resolve(
                List.of(item("汤", null, null, new BigDecimal("45"), null, null)), true).get(0);
        assertNull(resolved.getPer100gGl());
        assertEquals(List.of("每100g碳水"), resolved.getMissingFields());
        assertEquals(MealSuitabilityDtos.GI_SOURCE_CLIENT, resolved.getGiSource());
    }

    @Test void saveModeUserCorrectedKeepsClientGi() {
        // save 模式：userCorrected=1 的行按客户端值优先，人工修正不被库值顶掉
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var source = item("米饭", null, 4, new BigDecimal("50"), "MANUAL", 95);
        source.setUserCorrected(Boolean.TRUE);
        var resolved = resolver.resolve(List.of(source), false).get(0);
        assertEquals(new BigDecimal("50"), resolved.getGiValue());
        assertEquals(MealSuitabilityDtos.GI_SOURCE_CLIENT, resolved.getGiSource());
        assertEquals(MealSuitabilityDtos.GI_RELIABILITY_LOW, resolved.getGiReliability());
        // 碳水密度仍取库值：per100gGL = 50 × 25.9 / 100
        assertEquals(new BigDecimal("12.95"), resolved.getPer100gGl());
    }

    @Test void saveModeNonCorrectedUsesLibraryGi() {
        when(foods.selectList(any())).thenReturn(List.of(row(1L, "米饭", 1, 1, "MANUAL", "83.2")));
        var resolved = resolver.resolve(
                List.of(item("米饭", null, 4, new BigDecimal("50"), "AI_ESTIMATE", 95)), false).get(0);
        assertEquals(MealSuitabilityDtos.GI_SOURCE_LIBRARY, resolved.getGiSource());
        assertEquals(new BigDecimal("21.55"), resolved.getPer100gGl());
    }

    private void verifyNoWrites() {
        verify(foods, never()).insertRecognitionDraft(any());
        verify(foods, never()).attachRecognitionPhoto(any(), any());
        verify(foods, never()).insert(any(FoodNutrition.class));
        verify(foods, never()).updateById(any(FoodNutrition.class));
    }

    private static ItemInput item(String name, Long libraryId, Integer category,
                                  BigDecimal gi, String source, Integer confidence) {
        ItemInput input = new ItemInput();
        input.setFoodName(name);
        input.setLibraryFoodId(libraryId);
        input.setCategoryCode(category);
        input.setGiValue(gi);
        input.setNutritionSource(source);
        input.setConfidence(confidence);
        return input;
    }

    private static FoodNutrition row(long id, String name, int shelf, int category, String source, String gi) {
        FoodNutrition food = new FoodNutrition();
        food.setId(id);
        food.setFoodName(name);
        food.setCategoryCode(category);
        food.setShelfStatus(shelf);
        food.setNutritionSource(source);
        food.setGiValue(gi == null ? null : new BigDecimal(gi));
        food.setCarbsPer100g(new BigDecimal("25.9"));
        food.setCaloriesPer100g(new BigDecimal("116"));
        return food;
    }
}
