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

    // ---- 七类：无补充基线 / 份量 / 药物 / 运动 / 档案分档 / 目标锚点 / 边界 ----

    @Test
    void baselineNoSupplement() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        assertValue("8.4", r.getPredictNow());
        assertValue("7.8", r.getPredictSuggest());
        assertEquals("v1", r.getAlgorithmVersion());
        assertEquals("建议本餐摄入碳水约 45 g（约米饭 173 g）", r.getSuggestText());
    }

    @Test
    void portionCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setPortionRatio(new BigDecimal("0.5"));

        PredictionDtos.PredictResult r = service.predict(USER_ID, req);

        assertValue("7.2", r.getPredictNow());
    }

    @Test
    void medicationCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setMedicationTaken(true);

        assertValue("7.9", service.predict(USER_ID, req).getPredictNow());
    }

    @Test
    void exerciseCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setExerciseDone(true);

        assertValue("8.1", service.predict(USER_ID, req).getPredictNow());
    }

    @Test
    void typeBuckets() {
        // R：0/3/4→0.04、1→0.06、2→0.05（注意：方案 §7 案例 5 中「1→8.8」为笔误，按系数正确值应为 9.6）
        Object[][] cases = {{0, "8.4"}, {1, "9.6"}, {2, "9.0"}, {3, "8.4"}, {4, "8.4"}};
        for (Object[] c : cases) {
            int type = (Integer) c[0];
            String expected = (String) c[1];
            when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(type, null, null));
            PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));
            assertValue(expected, r.getPredictNow());
        }
    }

    @Test
    void targetDefaultWhenBothNull() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "0"));

        assertValue("7.8", r.getPredictSuggest());
        assertEquals("建议本餐摄入碳水约 45 g（约米饭 173 g）", r.getSuggestText());
    }

    @Test
    void targetFastingFallback() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, "6.4"));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "0"));

        assertValue("6.4", r.getPredictSuggest());
        assertEquals("建议本餐摄入碳水约 10 g（约米饭 38 g）", r.getSuggestText());
    }

    @Test
    void targetCustomPostprandial() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, "8.5", null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "0"));

        assertValue("8.5", r.getPredictSuggest());
    }

    @Test
    void zeroCarbsStillPredicts() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "0"));

        assertValue("6.0", r.getPredictNow());
        assertValue("7.8", r.getPredictSuggest());
    }

    @Test
    void highGlucoseForcesZeroSuggestAndWarns() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "15.0", "0"));

        assertValue("15.0", r.getPredictNow());
        assertValue("15.0", r.getPredictSuggest());
        assertEquals("当前血糖偏高，建议暂缓进食并继续监测，必要时就医", r.getSuggestText());
    }

    @Test
    void clampsOutOfRangeToHighBound() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(1, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "39.0", "50"));

        assertValue("39.9", r.getPredictNow());
    }

    // ---- 未建档：diabetesType=0 + 目标 null → 默认参数，正常返回 ----

    @Test
    void unarchivedUsesDefaultsAndReadsHealthOnce() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        assertValue("8.4", r.getPredictNow());
        assertValue("7.8", r.getPredictSuggest());
        assertEquals("v1", r.getAlgorithmVersion());
        verify(healthProfileService).healthViewOf(USER_ID);
    }

    // ---- 错误码：nodeCode 非餐点 → BIZ_REJECTED（40001） ----

    @Test
    void rejectsNonMealNode() {
        BizException e = assertThrows(BizException.class,
                () -> service.predict(USER_ID, request("before_lunch", "6.0", "60")));
        assertEquals(40001, e.getErrorCode().getCode());
    }

    // ---- 分食物建议：三档 / 削减 / 保底 / 降级 ----

    @Test
    void foodSuggestThreeTiers() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "93");
        req.setItems(List.of(
                item("米饭", 1, "150", "40"),
                item("红烧肉", 2, "100", "5"),
                item("青菜", 3, "100", "3"),
                item("可乐", 8, "300", "30"),
                item("薯片", 9, "30", "15")));

        PredictionDtos.PredictResult r = service.predict(USER_ID, req);

        assertEquals("米饭 139g · 红烧肉 正常 · 青菜 正常 · 可乐 不建议 · 薯片 不建议", r.getSuggestText());
        assertValue("45.0", r.getSuggestCarbs());
        // 结构化建议与文案同源：削减档给克重/0，保量档 null（正常）
        assertSuggest(r.getSuggestItems(), "米饭", "139");
        assertSuggest(r.getSuggestItems(), "红烧肉", null);
        assertSuggest(r.getSuggestItems(), "青菜", null);
        assertSuggest(r.getSuggestItems(), "可乐", "0");
        assertSuggest(r.getSuggestItems(), "薯片", "0");
    }

    @Test
    void foodSuggestMainReduceHalf() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "50");
        req.setItems(List.of(
                item("可乐", 8, "300", "25"),
                item("奶茶", 8, "300", "25")));

        assertEquals("可乐 减半 · 奶茶 减半", service.predict(USER_ID, req).getSuggestText());
        // 减半语气 → 结构化建议取半量克重
        PredictionDtos.PredictResult r = service.predict(USER_ID, req);
        assertSuggest(r.getSuggestItems(), "可乐", "150");
        assertSuggest(r.getSuggestItems(), "奶茶", "150");
    }

    @Test
    void foodSuggestMainReduceExactlyCoverKeepsBalanced() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        // 待削量恰好被主缩档碳水覆盖（remain=0）：均衡档保持原量，不再触发均衡分摊（旧实现该路径 NPE）
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "70");
        req.setItems(List.of(
                item("米饭", 1, "100", "40"),
                item("可乐", 8, "300", "25")));

        PredictionDtos.PredictResult r = service.predict(USER_ID, req);

        assertEquals("米饭 正常 · 可乐 建议替换为无糖/少糖", r.getSuggestText());
        assertSuggest(r.getSuggestItems(), "米饭", null);
        assertSuggest(r.getSuggestItems(), "可乐", "0");
    }

    @Test
    void foodSuggestStapleFloor() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "100");
        req.setItems(List.of(item("米饭", 1, "100", "100")));

        // 建议克重 45g，不低于主食保底 30g
        assertEquals("米饭 45g", service.predict(USER_ID, req).getSuggestText());
    }

    @Test
    void foodSuggestDegradesWhenCannotCutEnough() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "200");
        req.setItems(List.of(item("米饭", 1, "100", "200")));

        assertEquals("本次碳水整体偏高，建议整体减量或分餐食用", service.predict(USER_ID, req).getSuggestText());
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