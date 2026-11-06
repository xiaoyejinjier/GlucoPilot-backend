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
    // 期望值按 v2 公式手算：predictNow = cur + 0.441×(target−cur) + carbs×portion×R + dMed + dEx
    // 默认 target=7.8；cur=6.0 时收缩项 = 0.441×1.8 = 0.7938

    @Test
    void baselineNoSupplement() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        // 6.0 + 0.7938 + 60×0.0194(1.164) = 7.9578 → 8.0
        assertValue("8.0", r.getPredictNow());
        // 反解碳水 = 0.559×1.8 ÷ 0.0194 = 51.865979 → 51.9；回代恰为 7.8
        assertValue("7.8", r.getPredictSuggest());
        assertValue("51.9", r.getSuggestCarbs());
        assertEquals("v2", r.getAlgorithmVersion());
        assertEquals("建议本餐摄入碳水约 52 g（约米饭 199 g）", r.getSuggestText());
    }

    @Test
    void portionCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setPortionRatio(new BigDecimal("0.5"));

        PredictionDtos.PredictResult r = service.predict(USER_ID, req);

        // 6.0 + 0.7938 + 60×0.5×0.0194(0.582) = 7.3758 → 7.4
        assertValue("7.4", r.getPredictNow());
    }

    @Test
    void medicationCorrection() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));
        PredictionDtos.PredictRequest req = request("lunch", "6.0", "60");
        req.setMedicationTaken(true);

        // 7.9578 − 0.5 = 7.4578 → 7.5
        assertValue("7.5", service.predict(USER_ID, req).getPredictNow());
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
        // v2 R：0/3/4→0.0194、1→0.0290、2→0.0242；基础项 6.0 + 0.7938
        Object[][] cases = {{0, "8.0"}, {1, "8.5"}, {2, "8.2"}, {3, "8.0"}, {4, "8.0"}};
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
        assertEquals("建议本餐摄入碳水约 52 g（约米饭 199 g）", r.getSuggestText());
    }

    @Test
    void targetFastingFallback() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, "6.4"));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "0"));

        // 目标 6.4：反解碳水 = 0.559×0.4 ÷ 0.0194 = 11.525773 → 11.5，约米饭 44 g
        assertValue("6.4", r.getPredictSuggest());
        assertEquals("建议本餐摄入碳水约 12 g（约米饭 44 g）", r.getSuggestText());
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

        // v2 下无碳水也会向目标收缩：6.0 + 0.7938 = 6.7938 → 6.8
        assertValue("6.8", r.getPredictNow());
        assertValue("7.8", r.getPredictSuggest());
    }

    @Test
    void highGlucoseForcesZeroSuggestAndWarns() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "15.0", "0"));

        // 15.0 + 0.441×(7.8−15.0) = 15.0 − 3.1752 = 11.8248 → 11.8
        assertValue("11.8", r.getPredictNow());
        assertValue("11.8", r.getPredictSuggest());
        assertEquals("当前血糖偏高，建议暂缓进食并继续监测，必要时就医", r.getSuggestText());
    }

    @Test
    void clampsOutOfRangeToHighBound() {
        // v2 有收缩项后，仅靠「高当前血糖 + 碳水」已无法突破上限，
        // 故用高位目标（39.9）令收缩项为正：39.0 + 0.3969 + 50×0.029(1.45) = 40.8469 → clamp 39.9
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(1, "39.9", null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "39.0", "50"));

        assertValue("39.9", r.getPredictNow());
    }

    // ---- 未建档：diabetesType=0 + 目标 null → 默认参数，正常返回 ----

    @Test
    void unarchivedUsesDefaultsAndReadsHealthOnce() {
        when(healthProfileService.healthViewOf(USER_ID)).thenReturn(health(0, null, null));

        PredictionDtos.PredictResult r = service.predict(USER_ID, request("lunch", "6.0", "60"));

        assertValue("8.0", r.getPredictNow());
        assertValue("7.8", r.getPredictSuggest());
        assertEquals("v2", r.getAlgorithmVersion());
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

        // 待削量 = 93 − 51.865979 = 41.134021；主缩档碳水 30+15=45，削不完故不触发均衡分摊
        // 41.13 介于 主缩档一半(22.5) 与 全部(45) 之间 → 语气「建议替换为无糖/少糖」
        assertEquals("米饭 正常 · 红烧肉 正常 · 青菜 正常 · 可乐 建议替换为无糖/少糖 · 薯片 建议替换为无糖/少糖",
                r.getSuggestText());
        assertValue("51.9", r.getSuggestCarbs());
        // 结构化建议与文案同源：替换档给克重 0，保量/均衡档 null（正常）
        assertSuggest(r.getSuggestItems(), "米饭", null);
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

        // 建议碳水 51.9 > 实际 50，待削量为负 → 全部保持原量，不再触发「减半」
        assertEquals("可乐 正常 · 奶茶 正常", service.predict(USER_ID, req).getSuggestText());
        PredictionDtos.PredictResult r = service.predict(USER_ID, req);
        assertSuggest(r.getSuggestItems(), "可乐", null);
        assertSuggest(r.getSuggestItems(), "奶茶", null);
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

        // 建议碳水 51.865979 → 米饭克重 51.865979 g → 52 g，不低于主食保底 30 g
        assertEquals("米饭 52g", service.predict(USER_ID, req).getSuggestText());
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