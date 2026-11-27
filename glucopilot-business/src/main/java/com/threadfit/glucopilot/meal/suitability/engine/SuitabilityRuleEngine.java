package com.threadfit.glucopilot.meal.suitability.engine;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.FocusItem;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemResult;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 适食判定引擎（纯函数）：无 Spring 依赖、不读 DB、不写库。
 *
 * <h2>只判断「该不该吃」，不判断「吃了多少」</h2>
 * 2026-11-27 起份量彻底退出判定：识别出来的克重误差本来就大（同一盘菜两次识别能差一倍），
 * 用它去上下挪档位，等于把「识别噪声」当成「饮食建议」。所以：
 * <ul>
 *   <li>单项档位只由 per100gGL（每 100g 升糖负荷 = GI × 每 100g 碳水 / 100）决定，
 *       这是食物固有属性，同一种食物无论吃多少，档位都一样；</li>
 *   <li>克重与「这一份多少碳水/多少千卡」只用于营养展示与整餐合计，<b>不参与判定</b>；</li>
 *   <li>份量修正规则表（{@code meal_portion_adjust_rule}）保留不读——表还在，逻辑已下线，
 *       哪天要恢复有据可查。</li>
 * </ul>
 *
 * <h2>单项匹配</h2>
 * 每项按 priority 升序匹配规则（glMin/glMax 均含边界），取首个命中得到档位；
 * 不再用类别规则兜底（类别仅展示）。兼容旧行：metric 为 NULL 时按 GI 解释（giMin/giMax），
 * CARB 规则按每 100g 碳水匹配。库未命中或缺 GI/碳水密度 → UNKNOWN。
 *
 * <h2>整餐聚合：最差一档（worst-of）</h2>
 * 不做「碳水占比最大的那项定档」——那是拿量说话。既然判定只看「该不该吃」，
 * 一餐里只要有一道「不建议」，整餐就是「不建议」；全 UNKNOWN 才是 UNKNOWN。
 * 单项档位为 HIGH 时输出「重点关注」，原因只有 {@code INTRINSIC}（本身负担高）。
 */
public class SuitabilityRuleEngine {

    public static final String TIER_LOW = "LOW";
    public static final String TIER_MODERATE = "MODERATE";
    public static final String TIER_HIGH = "HIGH";
    public static final String TIER_UNKNOWN = "UNKNOWN";

    public static final String METRIC_GL = "GL";
    public static final String METRIC_GI = "GI";
    public static final String METRIC_CARB = "CARB";
    public static final String SCOPE_ITEM = "ITEM";
    public static final String SCOPE_MEAL = "MEAL";

    /** 严重度由低到高：整餐取最差档位时按这个顺序比较。 */
    private static final List<String> TIER_SEVERITY = List.of(TIER_LOW, TIER_MODERATE, TIER_HIGH);

    /**
     * 评估整餐。
     *
     * @param rules 同一 rule_version 下的规则（允许乱序、含禁用行，本方法自行过滤排序）
     * @param items 食物项（应先经 MealNutritionResolver 归一化，含 per100gGL）；为空或 null 时整餐 UNKNOWN
     */
    public MealSuitabilityDtos.Evaluation evaluate(List<FoodSuitabilityRule> rules, List<ItemInput> items) {
        List<FoodSuitabilityRule> ordered = orderRules(rules);

        List<ItemResult> results = new ArrayList<>();
        if (items != null) {
            for (ItemInput item : items) {
                if (item == null) {
                    continue;
                }
                results.add(evaluateItem(ordered, item));
            }
        }

        MealSuitabilityDtos.Evaluation evaluation = new MealSuitabilityDtos.Evaluation();
        evaluation.setItems(results);
        evaluation.setOverallTier(worstTierOf(results));
        evaluation.setWorstRuleCode(worstRuleCodeOf(results, evaluation.getOverallTier()));
        evaluation.setFocusItems(focusItemsOf(results));
        return evaluation;
    }

    /** 单项评估：per100gGL 规则匹配 → 档位 + 重点关注标记（不做任何份量修正）。 */
    private ItemResult evaluateItem(List<FoodSuitabilityRule> ordered, ItemInput item) {
        ItemResult result = new ItemResult();
        result.setFoodId(item.getFoodId());
        result.setFoodName(item.getFoodName());
        result.setPer100gGl(item.getPer100gGl());
        result.setGiSource(item.getGiSource());
        result.setGiReliability(item.getGiReliability());
        result.setMissingFields(item.getMissingFields());
        result.setFocused(false);

        FoodSuitabilityRule hit = firstMatch(ordered, item);
        if (hit == null) {
            result.setTier(TIER_UNKNOWN);
            result.setBaseTier(TIER_UNKNOWN);
            result.setRuleCode(null);
            return result;
        }
        result.setTier(hit.getTier());
        // baseTier 是「份量修正前档位」的历史字段，现在没有修正，恒等于 tier；
        // 保留它是为了不改前端契约（前端有少量读 baseTier 的展示逻辑）。
        result.setBaseTier(hit.getTier());
        result.setRuleCode(hit.getRuleCode());
        if (TIER_HIGH.equals(hit.getTier())) {
            result.setFocused(true);
            result.setFocusReason(MealSuitabilityDtos.FOCUS_INTRINSIC);
        }
        return result;
    }

    /**
     * 整餐档位 = 最差一档（LOW &lt; MODERATE &lt; HIGH），UNKNOWN 不参与比较；
     * 全部 UNKNOWN 或没有明细时返回 UNKNOWN。
     */
    private String worstTierOf(List<ItemResult> results) {
        String worst = null;
        int worstRank = -1;
        for (ItemResult result : results) {
            int rank = TIER_SEVERITY.indexOf(result.getTier());
            if (rank > worstRank) {
                worstRank = rank;
                worst = result.getTier();
            }
        }
        return worst == null ? TIER_UNKNOWN : worst;
    }

    /** 整餐代表规则：取第一个达到最差档位的项（前端用它解释「为什么这么说」）。 */
    private String worstRuleCodeOf(List<ItemResult> results, String overallTier) {
        if (overallTier == null || TIER_UNKNOWN.equals(overallTier)) {
            return null;
        }
        for (ItemResult result : results) {
            if (overallTier.equals(result.getTier())) {
                return result.getRuleCode();
            }
        }
        return null;
    }

    private List<FocusItem> focusItemsOf(List<ItemResult> results) {
        List<FocusItem> focusItems = new ArrayList<>();
        for (ItemResult result : results) {
            if (Boolean.TRUE.equals(result.getFocused())) {
                FocusItem focus = new FocusItem();
                focus.setFoodName(result.getFoodName());
                focus.setReason(result.getFocusReason());
                focusItems.add(focus);
            }
        }
        return focusItems;
    }

    /** 过滤禁用/无效/MEAL 域规则，按 priority 升序（null 视为最低优先级）。 */
    private List<FoodSuitabilityRule> orderRules(List<FoodSuitabilityRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<FoodSuitabilityRule> ordered = new ArrayList<>(rules.size());
        for (FoodSuitabilityRule rule : rules) {
            if (rule == null || rule.getIsEnabled() == null || rule.getIsEnabled() != 1) {
                continue;
            }
            if (!TIER_SEVERITY.contains(rule.getTier())) {
                continue;
            }
            if (SCOPE_MEAL.equals(rule.getScope())) {
                continue; // MEAL 域规则不参与单项匹配（整餐为 worst-of 聚合，预留扩展）
            }
            ordered.add(rule);
        }
        ordered.sort(Comparator.comparingInt(
                (FoodSuitabilityRule rule) -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority()));
        return ordered;
    }

    private FoodSuitabilityRule firstMatch(List<FoodSuitabilityRule> ordered, ItemInput item) {
        for (FoodSuitabilityRule rule : ordered) {
            if (matches(rule, item)) {
                return rule;
            }
        }
        return null;
    }

    /** 按 metric 匹配：GL→per100gGL，GI（含旧行 NULL）→giValue，CARB→每100g碳水；均含边界。 */
    private boolean matches(FoodSuitabilityRule rule, ItemInput item) {
        if (rule.getCategoryCode() != null && !rule.getCategoryCode().equals(item.getCategoryCode())) {
            return false;
        }
        return switch (metricOf(rule)) {
            case METRIC_GL -> inRange(item.getPer100gGl(), rule.getGlMin(), rule.getGlMax());
            case METRIC_CARB -> inRange(item.getCarbsPer100g(), rule.getCarbsMin(), rule.getCarbsMax());
            default -> inRange(item.getGiValue(), rule.getGiMin(), rule.getGiMax());
        };
    }

    private String metricOf(FoodSuitabilityRule rule) {
        if (rule.getMetric() != null) {
            return rule.getMetric();
        }
        // 旧行（V88 时代）没有 metric：带 carbs 区间的按 CARB 解释，其余按 GI
        return rule.getCarbsMin() != null || rule.getCarbsMax() != null ? METRIC_CARB : METRIC_GI;
    }

    /** 区间匹配：min/max 均含边界；value 为 null 时除「区间全开」外一律不命中（不用 0 冒充）。 */
    private boolean inRange(java.math.BigDecimal value, java.math.BigDecimal min, java.math.BigDecimal max) {
        if (min == null && max == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        if (min != null && value.compareTo(min) < 0) {
            return false;
        }
        return max == null || value.compareTo(max) <= 0;
    }
}
