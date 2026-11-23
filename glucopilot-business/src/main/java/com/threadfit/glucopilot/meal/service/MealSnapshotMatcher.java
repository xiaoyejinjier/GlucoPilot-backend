package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.ai.business.meal.service.MealFoodCatalogService;
import com.threadfit.glucopilot.common.analysis.MealAnalysisSnapshotStore;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 识别快照 ↔ 保存明细的匹配与低置信判定。
 *
 * <p>纯静态工具，不依赖 Spring / 不做 IO，便于单测。调用方负责先做「token 是否存在、userId 与 mediaId
 * 是否匹配」的校验，这里只处理「快照已确认属于本用户本餐」之后的纯内存逻辑。
 *
 * <p>匹配顺序（逐级回退，均按请求顺序一一配对，禁止 {@code map.get(name)} 取第一条）：
 * <ol>
 *   <li>{@code libraryFoodId} 精确；</li>
 *   <li>{@code foodId} 精确，同 id 多项按出现序号配对；</li>
 *   <li>归一化名称，同名多项按出现序号配对。</li>
 * </ol>
 * 仍匹配不上的明细视为「无证据」，不参与拦截，只记 WARN。
 *
 * <p>快照里的 id 是字符串、保存侧是数值，比较统一走 {@code String.valueOf}，不做 parseLong
 * （脏值不抛异常，最坏只是匹配不上）。
 */
@Slf4j
public final class MealSnapshotMatcher {

    private static final String STATUS_AVAILABLE = "AVAILABLE";

    private MealSnapshotMatcher() {
    }

    /**
     * 用快照对齐 {@code libraryStatus}（命中项以识别那一刻的值为准），并返回低置信项标签。
     *
     * <p><b>会就地修改</b> {@code items} 的 {@code libraryStatus}：这是有意行为，保存落库要用
     * 「识别时的状态」而不是保存时重算的状态，避免运营中途下架导致用户「什么都没改却存不进去」。
     *
     * @return 低置信标签，格式 {@code #2 米饭}，序号是请求明细的 1-based 下标
     */
    public static List<String> alignAndEvaluate(List<ItemInput> items,
                                                MealAnalysisSnapshotStore.Snapshot snapshot,
                                                int threshold) {
        List<MealAnalysisSnapshotStore.SnapshotItem> snaps =
                snapshot == null || snapshot.getItems() == null ? List.of() : snapshot.getItems();
        MealAnalysisSnapshotStore.SnapshotItem[] matched = match(items, snaps);

        List<String> labels = new ArrayList<>();
        int manualSkipped = 0;
        int correctedSkipped = 0;
        int unmatchedSkipped = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemInput item = items.get(i);
            MealAnalysisSnapshotStore.SnapshotItem snap = matched[i];
            // 手输项：无论是否与快照同名，都不进低置信集合——用户自己填的东西不该被要求"确认机器识别"
            if (isManual(item)) {
                manualSkipped++;
                continue;
            }
            // 用户已主动修正的项：不再按机器置信度判定
            if (Boolean.TRUE.equals(item.getUserCorrected())) {
                correctedSkipped++;
                continue;
            }
            if (snapshot != null && snap == null) {
                unmatchedSkipped++;
                continue;
            }
            if (snap != null) {
                item.setLibraryStatus(snap.getLibraryStatus());
            }
            if (isLowConfidence(item, snap, threshold)) {
                labels.add("#" + (i + 1) + " " + item.getFoodName());
            }
        }
        if (manualSkipped + correctedSkipped + unmatchedSkipped > 0) {
            log.warn("低置信判定跳过部分明细：手输 {} 项、已人工修正 {} 项、快照未匹配 {} 项",
                    manualSkipped, correctedSkipped, unmatchedSkipped);
        }
        return labels;
    }

    /** 手输项：无库内 id 也无识别草稿 id。名称被 resolver 匹配成库内条目后就不再算手输。 */
    private static boolean isManual(ItemInput item) {
        return item.getFoodId() == null && item.getLibraryFoodId() == null;
    }

    private static boolean isLowConfidence(ItemInput item,
                                           MealAnalysisSnapshotStore.SnapshotItem snap,
                                           int threshold) {
        // 置信度只认快照（保存路径不采信客户端值；无快照时为 null）
        Integer confidence = snap == null ? null : snap.getConfidence();
        if (confidence != null && confidence < threshold) {
            return true;
        }
        // 未知状态默认判为需确认，属安全侧
        return !STATUS_AVAILABLE.equals(item.getLibraryStatus());
    }

    /** 三级匹配，返回与 items 等长的数组，未匹配为 null。 */
    private static MealAnalysisSnapshotStore.SnapshotItem[] match(
            List<ItemInput> items, List<MealAnalysisSnapshotStore.SnapshotItem> snaps) {
        MealAnalysisSnapshotStore.SnapshotItem[] matched = new MealAnalysisSnapshotStore.SnapshotItem[items.size()];
        boolean[] used = new boolean[snaps.size()];

        // ① libraryFoodId 精确
        for (int i = 0; i < items.size(); i++) {
            Long libraryId = items.get(i).getLibraryFoodId();
            if (libraryId == null) continue;
            for (int j = 0; j < snaps.size(); j++) {
                if (used[j]) continue;
                if (idEquals(snaps.get(j).getLibraryFoodId(), libraryId)) {
                    matched[i] = snaps.get(j);
                    used[j] = true;
                    break;
                }
            }
        }
        // ② foodId 精确，同 id 多项按出现序号配对
        pairByKey(items, matched, snaps, used,
                item -> idToKey(item.getFoodId()),
                snap -> trimOrNull(snap.getFoodId()));
        // ③ 归一化名称，同名多项按出现序号配对
        pairByKey(items, matched, snaps, used,
                item -> normalize(item.getFoodName()),
                snap -> normalize(snap.getFoodName()));
        return matched;
    }

    private static void pairByKey(List<ItemInput> items,
                                  MealAnalysisSnapshotStore.SnapshotItem[] matched,
                                  List<MealAnalysisSnapshotStore.SnapshotItem> snaps,
                                  boolean[] used,
                                  Function<ItemInput, String> itemKey,
                                  Function<MealAnalysisSnapshotStore.SnapshotItem, String> snapKey) {
        // 快照侧：key → 未使用下标列表（保持快照原顺序）
        Map<String, List<Integer>> snapIndexByKey = new LinkedHashMap<>();
        for (int j = 0; j < snaps.size(); j++) {
            if (used[j]) continue;
            String key = snapKey.apply(snaps.get(j));
            if (key == null || key.isEmpty()) continue;
            snapIndexByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(j);
        }
        // 请求侧：按出现序号依次消耗同 key 的快照项，禁止"取第一条"
        Map<String, Integer> cursor = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            if (matched[i] != null) continue;
            String key = itemKey.apply(items.get(i));
            if (key == null || key.isEmpty()) continue;
            List<Integer> candidates = snapIndexByKey.get(key);
            if (candidates == null) continue;
            int ordinal = cursor.merge(key, 1, Integer::sum) - 1;
            if (ordinal >= candidates.size()) continue;
            int j = candidates.get(ordinal);
            matched[i] = snaps.get(j);
            used[j] = true;
        }
    }

    /**
     * 快照 id 是字符串、保存侧是数值：统一 {@code String.valueOf} 比较，不做 parseLong，
     * 脏值最坏只是匹配不上，不会把保存链路抛崩。
     */
    private static boolean idEquals(String snapshotId, Long saveId) {
        return snapshotId != null && saveId != null && snapshotId.trim().equals(String.valueOf(saveId));
    }

    private static String idToKey(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    /** 复用识别链路的归一化实现，不另写一份，避免两套规则漂移。 */
    private static String normalize(String foodName) {
        return foodName == null ? "" : MealFoodCatalogService.normalizeName(foodName).trim();
    }
}
