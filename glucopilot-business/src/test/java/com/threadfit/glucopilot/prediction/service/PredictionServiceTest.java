package com.threadfit.glucopilot.prediction.service;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.prediction.config.PredictionProperties;
import com.threadfit.glucopilot.prediction.dto.PredictionDtos;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 语义：收缩锚点恒 7.8、离散搜索 + planCarbs 复算、状态机（dangerHigh=13.9）。
 * predictNow = cur + 0.441×(7.8−cur) + carbs×portion×R + dMed + dEx；R：0/3/4=0.0194、1=0.0290、2=0.0242。
 */
@ExtendWith(MockitoExtension.class)
class PredictionServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private HealthProfileService healthProfileService;

    private PredictionService service;

    @BeforeEach
    void setUp() {
        service = new PredictionService(new PredictionProperties(), healthProfileService);
    }

    // ---- 七类：基线 / 份量 / 药物 / 运动 / 分档 / 状态机 / 边界 ----

    @Test
    void baselineInRange() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        // 6.0 + 0.7938 + 60×0.0194(1.164) = 7.9578 → 8.0，∈ [4.4,10.0] → IN_RANGE
        assertValue("8.0", r.getPredictNow());
        assertValue("8.0", r.getPredictSuggest());
        assertValue("60.0", r.getSuggestCarbs());
        assertEquals("IN_RANGE", r.getRecommendationStatus());
        assertEquals("v2.1", r.getAlgorithmVersion());
        assertEquals("GENERAL", r.getEffectiveProfile());
        assertEquals("GENERAL", r.getTargetSource());
        assertValue("4.4", r.getTargetLower());
        assertValue("10.0", r.getTargetUpper());
        assertEquals(List.of(), r.getStatusHints());
        assertEquals("当前份量预计血糖在目标范围内，无需调整", r.getSuggestText());
    }

    @Test
    void portionCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setPortionRatio(new BigDecimal("0.5"));

        // 6.0 + 0.7938 + 60×0.5×0.0194(0.582) = 7.3758 → 7.4
        assertValue("7.4", service.predict(USER_ID, req).getPredictNow());
    }

    @Test
    void medicationCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setMedicationTaken(true);
        assertValue("8.0", service.predict(USER_ID, req).getPredictNow());
    }

    @Test
    void exerciseCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setExerciseDone(true);

        // 7.9578 − 0.3 = 7.6578 → 7.7
        assertValue("7.7", service.predict(USER_ID, req).getPredictNow());
    }

    @Test
    void typeBuckets() {
        Object[][] cases = {{0, "8.0"}, {1, "8.5"}, {2, "8.2"}, {3, "8.0"}, {4, "8.0"}};
        for (Object[] c : cases) {
            int type = (Integer) c[0];
            String expected = (String) c[1];
            when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(type, null, null));
            assertValue(expected, service.predict(USER_ID, request("lunch", "6.0", "60")).getPredictNow());
        }
    }

    @Test
    void zeroCarbsShrinksToTargetAnchor() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "0"));

        // 6.0 + 0.7938 = 6.7938 → 6.8，∈ 区间 → IN_RANGE，推荐=当前
        assertValue("6.8", r.getPredictNow());
        assertValue("6.8", r.getPredictSuggest());
        assertValue("0.0", r.getSuggestCarbs());
        assertEquals("IN_RANGE", r.getRecommendationStatus());
    }

    @Test
    void highGlucoseDanger() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "15.0", "0"));

        // 15.0 + 0.441×(7.8−15.0) = 15.0 − 3.1752 = 11.8248 → 11.8；cur≥13.9 → DANGER
        assertValue("11.8", r.getPredictNow());
        assertNull(r.getPredictSuggest());
        assertValue("0", r.getSuggestCarbs());
        assertEquals("DANGER", r.getRecommendationStatus());
        assertEquals(List.of("danger_high"), r.getStatusHints());
        assertEquals(false, r.getCaution());
        assertEquals("当前血糖明显偏高，建议暂缓进食并尽快就医", r.getSuggestText());
    }

    @Test
    void cautionWhenHighButNotDanger() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        // cur=11 < 13.9 非 DANGER，但 ≥10 叠加 CAUTION；predictNow=9.6 ∈ 区间 → IN_RANGE
        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "11.0", "0"));
        assertValue("9.6", r.getPredictNow());
        assertEquals("IN_RANGE", r.getRecommendationStatus());
        assertEquals(true, r.getCaution());
        assertEquals(List.of("caution_high"), r.getStatusHints());
    }

    @Test
    void lowGlucosePriority() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "3.0", "0"));

        // cur=3.0 < 3.9 → LOW；不减不加量，predictSuggest=predictNow
        assertValue("5.1", r.getPredictNow());
        assertValue("5.1", r.getPredictSuggest());
        assertEquals("LOW", r.getRecommendationStatus());
        assertEquals(List.of("low_first"), r.getStatusHints());
    }

    @Test
    void aboveSearchesDownward() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        // cur=6、carbs=200：predictNow=10.7 > 10.0 → ABOVE；搜索向下，ρ=0.5 时 predicted=8.7 + margin 1.0 ≤ 10.0
        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "200"));

        assertValue("10.7", r.getPredictNow());
        assertEquals("ABOVE", r.getRecommendationStatus());
        assertValue("8.7", r.getPredictSuggest());
        assertValue("100.0", r.getSuggestCarbs());
        assertEquals(List.of("above_adjusted"), r.getStatusHints());
    }

    @Test
    void infeasibleGdm() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(3, null, null));

        // GDM 上限 6.7、margin 0.3，需 predicted≤6.4；cur=6 无碳水预测 6.8 已超 → 全部档不达标 → INFEASIBLE
        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "30"));

        assertEquals("INFEASIBLE", r.getRecommendationStatus());
        assertNull(r.getPredictSuggest());
        assertNull(r.getSuggestCarbs());
        assertEquals("GDM", r.getEffectiveProfile());
        assertEquals(List.of("gdm_infeasible"), r.getStatusHints());
    }

    @Test
    void infeasibleNormal() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(healthGoal(0, 1));

        // YOUNG 上限 7.8、margin 1.0，需 predicted≤6.8；cur=6 无碳水预测 6.8，60g 最低档 30g 预测 7.4 → INFEASIBLE
        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        assertEquals("INFEASIBLE", r.getRecommendationStatus());
        assertEquals(List.of("infeasible_normal"), r.getStatusHints());
        assertEquals("YOUNG_NO_COMPLICATION", r.getEffectiveProfile());
    }

    @Test
    void foodRatioReduction() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "200");
        req.setItems(List.of(
                item("米饭", 1, "100", "100"),
                item("可乐", 8, "300", "100")));

        PredictionDtos.PredictResult r = service.predict(USER_ID, req);

        // ABOVE 搜索命中 ρ=0.5：米饭 100→50g（主食保底 50g）、可乐 300→150g；planCarbs=100，预测 8.7
        assertEquals("ABOVE", r.getRecommendationStatus());
        assertValue("8.7", r.getPredictSuggest());
        assertValue("100.0", r.getSuggestCarbs());
        assertEquals("米饭 50g · 可乐 150g", r.getSuggestText());
        assertSuggest(r.getSuggestItems(), "米饭", "50");
        assertSuggest(r.getSuggestItems(), "可乐", "150");
    }

    @Test
    void unarchivedUsesDefaultsAndReadsHealthOnce() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        assertValue("8.0", r.getPredictNow());
        assertValue("8.0", r.getPredictSuggest());
        assertEquals("v2.1", r.getAlgorithmVersion());
        verify(healthProfileService).healthViewOf(USER_ID);
    }

    // ---- 错误码：nodeCode 非餐点 → BIZ_REJECTED（40001） ----

    @Test
    void rejectsNonMealNode() {
        BizException e = assertThrows(BizException.class,
                () -> service.predict(USER_ID, request("before_lunch", "6.0", "60")));
        assertEquals(40001, e.getErrorCode().getCode());
    }

    // ---- 工具 ----

    private static PredictionDtos.PredictRequest request(String nodeCode, String cur, String carbs) {
        PredictionDtos.PredictRequest r = new PredictionDtos.PredictRequest();
        r.setNodeCode(nodeCode);
        r.setCurrentGlucose(new BigDecimal(cur));
        r.setCarbsG(new BigDecimal(carbs));
        return r;
    }

    private static PredictionDtos.Item item(String name, int category, String weight, String carbs) {
        PredictionDtos.Item it = new PredictionDtos.Item();
        it.setFoodName(name);
        it.setCategoryCode(category);
        it.setWeightG(new BigDecimal(weight));
        it.setCarbsG(new BigDecimal(carbs));
        return it;
    }

    private static HealthView health(Integer type, String targetPostprandial, String targetFasting) {
        return HealthView.builder()
                .diabetesType(type)
                .targetPostprandial(targetPostprandial == null ? null : new BigDecimal(targetPostprandial))
                .targetFasting(targetFasting == null ? null : new BigDecimal(targetFasting))
                .build();
    }

    private static HealthView healthGoal(Integer type, Integer goalProfile) {
        return HealthView.builder()
                .diabetesType(type)
                .glucoseGoalProfile(goalProfile)
                .build();
    }

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }

    /** 按 foodName 定位结构化建议并断言克重；expected=null 表示「保持原份量（正常）」。 */
    private static void assertSuggest(List<PredictionDtos.SuggestItem> items, String foodName, String expected) {
        PredictionDtos.SuggestItem hit = items.stream()
                .filter(s -> foodName.equals(s.getFoodName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("建议中缺少 " + foodName));
        if (expected == null) {
            assertNull(hit.getSuggestWeight(), foodName + " 应为正常（null）");
        } else {
            assertEquals(0, new BigDecimal(expected).compareTo(hit.getSuggestWeight()),
                    foodName + " 期望 " + expected + " 实际 " + hit.getSuggestWeight());
        }
    }
}