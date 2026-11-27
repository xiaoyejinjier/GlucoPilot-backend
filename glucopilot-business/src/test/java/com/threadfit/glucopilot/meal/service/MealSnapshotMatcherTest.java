package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore.Snapshot;
import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore.SnapshotItem;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯函数单测：快照匹配 + 低置信判定（不启动 Spring、不连 Redis）。
 */
class MealSnapshotMatcherTest {

    private static final int THRESHOLD = 70;

    @Test
    void manualEntryIsExcludedFromLowConfidence() {
        // 手输项即使与快照同名、快照置信度很低，也不要求用户"确认机器识别"
        ItemInput item = item("自制沙拉", null, null);
        Snapshot snapshot = snapshot(snap("自制沙拉", null, null, 10, "PENDING_REVIEW"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertTrue(labels.isEmpty());
        assertNull(item.getLibraryStatus());
    }

    @Test
    void userCorrectedEntryIsExcludedFromLowConfidence() {
        ItemInput item = item("米饭", null, 500L);
        item.setUserCorrected(true);
        Snapshot snapshot = snapshot(snap("米饭", "600", "500", 20, "PENDING_REVIEW"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertTrue(labels.isEmpty());
        // 已修正项不再接受快照状态覆盖，保存时按客户端归一化结果落库
        assertNull(item.getLibraryStatus());
    }

    @Test
    void pairsSameNameByOrdinalAndFlagsOnlyTheLowOne() {
        // 两个"米饭"只标 #1；第二条必须配到第 2 个快照项，不能被第一条吞掉
        ItemInput first = item("米饭", null, 500L);
        ItemInput second = item("米饭", null, 501L);
        Snapshot snapshot = snapshot(
                snap("米饭", null, "500", 50, "PENDING_REVIEW"),
                snap("米饭", null, "501", 90, "AVAILABLE"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(
                List.of(first, second), snapshot, THRESHOLD);

        assertEquals(List.of("#1 米饭"), labels);
        assertEquals("PENDING_REVIEW", first.getLibraryStatus());
        assertEquals("AVAILABLE", second.getLibraryStatus());
    }

    @Test
    void matchesByStringifiedLibraryFoodId() {
        // 快照 id 是字符串、保存侧是 Long，必须按 String.valueOf 命中（不得 parseLong）
        ItemInput item = item("米饭", null, 500L);
        Snapshot snapshot = snapshot(snap("米饭", null, "500", 90, "PENDING_REVIEW"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertEquals(List.of("#1 米饭"), labels);
        assertEquals("PENDING_REVIEW", item.getLibraryStatus());
    }

    @Test
    void unmatchedItemIsNotIntercepted() {
        // 换食物导致匹配不上：视为无证据，不拦截、也不覆盖状态
        ItemInput item = item("杂粮饭", null, 999L);
        Snapshot snapshot = snapshot(snap("米饭", null, "500", 10, "UNRESOLVED"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertTrue(labels.isEmpty());
        assertNull(item.getLibraryStatus());
    }

    @Test
    void withoutSnapshotFallsBackToNormalizedStatus() {
        // 无快照（旧客户端/过期）：用保存时归一化出的 libraryStatus 判定，confidence 无来源不参与
        ItemInput available = item("鸡蛋", null, 500L);
        available.setLibraryStatus("AVAILABLE");
        ItemInput pending = item("米饭", null, 501L);
        pending.setLibraryStatus("PENDING_REVIEW");

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(
                List.of(available, pending), null, THRESHOLD);

        assertEquals(List.of("#2 米饭"), labels);
    }

    @Test
    void snapshotStatusOverridesStaleItemStatus() {
        // 保存时营养库已下架（归一化 AVAILABLE 变 UNRESOLVED），但识别那一刻是 PENDING_REVIEW，
        // 仍以快照为准判定，避免"什么都没改却存不进去"
        ItemInput item = item("米饭", null, 500L);
        item.setLibraryStatus("AVAILABLE");
        Snapshot snapshot = snapshot(snap("米饭", null, "500", 95, "UNRESOLVED"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertEquals(List.of("#1 米饭"), labels);
        assertEquals("UNRESOLVED", item.getLibraryStatus());
    }

    @Test
    void matchesByFoodIdWhenLibraryFoodIdAbsent() {
        ItemInput item = item("鸡蛋", 700L, null);
        Snapshot snapshot = snapshot(snap("鸡蛋", "700", null, 88, "AVAILABLE"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertTrue(labels.isEmpty());
        assertEquals("AVAILABLE", item.getLibraryStatus());
    }

    @Test
    void confidenceBelowThresholdFlagsEvenWhenAvailable() {
        ItemInput item = item("米饭", null, 500L);
        Snapshot snapshot = snapshot(snap("米饭", null, "500", 69, "AVAILABLE"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertEquals(List.of("#1 米饭"), labels);
    }

    @Test
    void labelsFollowRequestOrderWithOneBasedIndex() {
        // 文案序号取请求 item 的 1-based 下标（用户示例：#2 米饭、#5 杂粮饭）
        ItemInput a = item("鸡蛋", null, 500L);
        ItemInput b = item("米饭", null, 501L);
        ItemInput c = item("牛奶", null, 502L);
        b.setLibraryStatus("PENDING_REVIEW");
        c.setLibraryStatus("UNRESOLVED");
        Snapshot snapshot = snapshot(
                snap("鸡蛋", null, "500", 90, "AVAILABLE"),
                snap("米饭", null, "501", 90, "PENDING_REVIEW"),
                snap("牛奶", null, "502", 90, "UNRESOLVED"));

        List<String> labels = MealSnapshotMatcher.alignAndEvaluate(
                List.of(a, b, c), snapshot, THRESHOLD);

        assertEquals(List.of("#2 米饭", "#3 牛奶"), labels);
    }

    @Test
    void backfillsDishGroupingFromSnapshotWhenRequestOmitsIt() {
        // 老客户端不带 dishName/role/cookMethod：快照里有，就应该补上，
        // 否则升级前后同一顿饭的明细排版会突然变成一堆散装食材名
        ItemInput item = item("青椒", null, 500L);
        Snapshot snapshot = snapshot(new SnapshotItem("青椒", "青椒炒肉", "SEASONING", "炒",
                null, "500", null, null, null, "AVAILABLE", 95));

        MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertEquals("青椒炒肉", item.getDishName());
        assertEquals("SEASONING", item.getRole());
        assertEquals("炒", item.getCookMethod());
    }

    @Test
    void keepsUserProvidedDishGroupingOverSnapshot() {
        // 用户改过的值优先：请求里给了菜名与做法，就不该被快照覆盖回去
        ItemInput item = item("青椒", null, 500L);
        item.setDishName("我自己起的名字");
        item.setCookMethod("蒸");
        Snapshot snapshot = snapshot(new SnapshotItem("青椒", "青椒炒肉", "SEASONING", "炒",
                null, "500", null, null, null, "AVAILABLE", 95));

        MealSnapshotMatcher.alignAndEvaluate(List.of(item), snapshot, THRESHOLD);

        assertEquals("我自己起的名字", item.getDishName());
        assertEquals("蒸", item.getCookMethod());
        // 角色没给过，仍从快照补
        assertEquals("SEASONING", item.getRole());
    }
    private static ItemInput item(String foodName, Long foodId, Long libraryFoodId) {
        ItemInput input = new ItemInput();
        input.setFoodName(foodName);
        input.setFoodId(foodId);
        input.setLibraryFoodId(libraryFoodId);
        return input;
    }

    private static Snapshot snapshot(SnapshotItem... items) {
        Snapshot snapshot = new Snapshot();
        snapshot.setItems(new ArrayList<>(List.of(items)));
        return snapshot;
    }

    private static SnapshotItem snap(String foodName, String foodId, String libraryFoodId,
                                     Integer confidence, String libraryStatus) {
        return new SnapshotItem(foodName, foodId, libraryFoodId, null, null, null,
                libraryStatus, confidence);
    }
}
