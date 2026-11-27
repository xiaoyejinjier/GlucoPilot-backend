package com.threadfit.glucopilot.ai.business.meal.service;

import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.impl.MealAnalysisServiceImpl;

/**
 * 模型输出 → 食物库条目的归一化契约。
 *
 * <h2>为什么要这一步</h2>
 * 模型返回的是自由文本（"一碗牛肉面"），而营养计算与统计需要稳定条目与稳定热量口径。
 * 归一化做三件事：名称标准化（去空格/全半角/常见修饰）、命中已有条目、未命中则新建待审条目。
 *
 * <h2>为什么不直接相信模型给的营养数值</h2>
 * 同一道菜两次识别给出的热量可以差一倍；以食物库为准、模型只负责"认出是什么"，
 * 结果才可复现。模型给的数值仅在没有库条目时兜底。
 */
public interface MealFoodCatalogService {

    /** 主料。 */
    String ROLE_MAIN = "MAIN";

    /** 配料（葱、姜、蒜、辣椒等）：计入营养，但展示与纠正粒度不同。 */
    String ROLE_SEASONING = "SEASONING";

    /** 烹饪方式白名单：模型只能在这些值里选，其余一律落 null（不猜）。 */
    java.util.Set<String> COOK_METHODS = java.util.Set.of(
            "炒", "炸", "煎", "烤", "蒸", "煮", "炖", "凉拌", "生食", "其他");

    /** 菜品名上限：与 {@code meal_food_item.dish_name VARCHAR(64)} 对齐。 */
    int DISH_NAME_MAX_CHARS = 64;

    /**
     * 主料 / 配料归一化。
     *
     * <p>只认 {@code MAIN} 与 {@code SEASONING}（大小写与空白都容忍），其余一律按主料处理——
     * 宁可把配料当主料显示，也不要因为模型写了个 {@code "side"} 就让整条明细没有角色、
     * 前端分组逻辑再分支出一个"未知角色"分支。</p>
     */
    static String normalizeRole(String value) {
        if (value == null) {
            return ROLE_MAIN;
        }
        String trimmed = value.trim();
        return ROLE_SEASONING.equalsIgnoreCase(trimmed) ? ROLE_SEASONING : ROLE_MAIN;
    }

    /**
     * 烹饪方式归一化：不在白名单内的返回 null。
     *
     * <p>为什么不像角色那样兜底成「其他」：烹饪方式会直接展示给用户当纠正入口，
     * 一个模型瞎写的「红烧」被静默改成「其他」比留空更容易误导（留空前端就不显示这一项）。</p>
     */
    static String normalizeCookMethod(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return COOK_METHODS.contains(trimmed) ? trimmed : null;
    }

    /**
     * 菜品名归一化：空白折叠 + 截断到 64 字，空串返回 null（= 没有分组信息）。
     *
     * <p>截断而不是报错：菜品名只用于分组展示，多出的字对用户没有价值，
     * 但因此拒掉整份识别结果会浪费一次视觉调用。</p>
     */
    static String normalizeDishName(String value) {
        String normalized = normalizeName(value);
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= DISH_NAME_MAX_CHARS
                ? normalized : normalized.substring(0, DISH_NAME_MAX_CHARS);
    }

    /** 归一化一条模型识别结果；added=true 表示为此新建了库条目。 */
    Resolution resolve(MealAnalysisDtos.ModelFood input, String model);

    /** sourceMediaId is an ownership-verified source photo from meal recognition. */
    Resolution resolve(MealAnalysisDtos.ModelFood input, String model, Long sourceMediaId);

    /**
     * 名称标准化（NFKC 归一 + 空白折叠），供匹配与去重用。
     * <p>
     * 放在接口上是刻意的：{@code MealAnalysisServiceImpl} 也要用它来对齐"模型给的名字"与"库里的名字"，
     * 两处各写一份迟早会出现"同一条目被当成两条"。实现保持与原实现一致（NFKC + 折叠空白），不改行为。
     */
    static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    /**
     * 单条明细是否需要用户确认：置信度低于阈值，或未命中营养库（UNRESOLVED），或命中但待审（PENDING_REVIEW）。
     * <p>
     * 规则只写这一份：识别时用它给明细打标，幂等重放时用它按明细重算，
     * 否则"旧缓存里的标记"与"当前规则"会各说各话。
     * <p>
     * 未知库状态（非 AVAILABLE）一律判为需确认，属安全侧：将来新增状态时宁可多让用户看一眼。
     */
    static boolean needsConfirmation(int confidence, String libraryStatus, int threshold) {
        return confidence < threshold || !"AVAILABLE".equals(libraryStatus);
    }

    /**
     * 同名多行时的取行规则（单一实现，识别与餐食归一化共用）：
     * <b>上架优先 → 分类精确优先 → id 升序取第一条</b>。
     * <p>
     * 与数据库 {@code ORDER BY shelf_status DESC, id ASC} 等价，但只依赖传入集合，
     * 因此批量路径（一次 IN 查回多行）也能得到和单查完全一致的结论。
     */
    static com.threadfit.glucopilot.food.entity.FoodNutrition pickBest(
            java.util.List<com.threadfit.glucopilot.food.entity.FoodNutrition> candidates, int category) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        java.util.List<com.threadfit.glucopilot.food.entity.FoodNutrition> preferred = candidates.stream()
                .filter(food -> food.getShelfStatus() != null && food.getShelfStatus() == 1).toList();
        if (preferred.isEmpty()) {
            preferred = candidates;
        }
        java.util.Comparator<com.threadfit.glucopilot.food.entity.FoodNutrition> byId = java.util.Comparator
                .comparing(com.threadfit.glucopilot.food.entity.FoodNutrition::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        java.util.List<com.threadfit.glucopilot.food.entity.FoodNutrition> pool = preferred;
        return pool.stream()
                .filter(food -> food.getCategoryCode() != null && food.getCategoryCode() == category)
                .min(byId)
                .orElseGet(() -> pool.stream().min(byId).orElse(null));
    }

    /**
     * 只读匹配：按归一化名称找库内条目，<b>不建草稿、不补图片、不写库</b>。
     * <p>
     * 与 {@link #resolve} 共用 {@link #pickBest} 的取行规则，避免"识别标已上架、保存标待审"的规则漂移。
     */
    com.threadfit.glucopilot.food.entity.FoodNutrition findLibraryFood(String normalizedName, int category);

    /** 归一化结果：命中的条目（可为新建的）+ 是否新增。 */
    record Resolution(MealAnalysisDtos.Item item, boolean added) {
    }
}