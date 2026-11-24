package com.threadfit.glucopilot.meal.suitability.engine;

import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.FocusItem;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemInput;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos.ItemResult;
import com.threadfit.glucopilot.meal.suitability.entity.FoodSuitabilityRule;
import com.threadfit.glucopilot.meal.suitability.entity.MealPortionAdjustRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 适食判定引擎（纯函数）：无 Spring 依赖、不读 DB、不写库。
 *
 * <h2>判定主指标：per100gGL（每 100g 升糖负荷）</h2>
 * per100gGL = GI × 每100g碳水 / 100，食物固有属性，不随份量变化；
 * 识别重量的 ±50% 误差不影响档位。库未命中或缺 GI/碳水密度时降级 UNKNOWN。
 *
 * <h2>两阶段匹配</h2>
 * <ol>
 *   <li><b>ITEM</b>：每项按 priority 升序匹配 metric=GL 的规则（glMin/glMax 均含边界），
 *       取首个命中得到 per100gGL 档位（baseTier）；不再用类别规则兜底（类别仅展示）。
 *       兼容旧行：metric 为 NULL 按 GI 解释（giMin/giMax），CARB 规则按每100g碳水匹配，
 *       不存在「带 carbsMin/carbsMax 被短路」的路径。</li>
 *   <li><b>份量修正</b>：按该份 carbsG 量级桶查 {@link MealPortionAdjustRule}
 *       （桶下限含、上限不含），只做温和修正：MODERATE 上下挪一档，HIGH 不降、LOW 不升。
 *       阈值全部落表，本类不写死任何数值。</li>
 * </ol>
 *
 * <h2>整餐聚合：主体定性 + 元凶标记（取消 worst-of）</h2>
 * 整餐档位 = <b>碳水占比最大的非 UNKNOWN 项</b>（份量修正后档位）；
 * carbsG 缺失项排除出占比分母；全 UNKNOWN → 整餐 UNKNOWN。
 * 单项最终档位为 HIGH 时输出「重点关注」标记（reason 区分本身偏高 / 份量偏大），
 * <b>只标注元凶，不把整餐拉为 HIGH</b>——不存在「上调一档」逻辑。
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

    /** 严重度由低到高（仅用于 HIGH 判定与展示排序，不再用于整餐聚合）。 */
    private static final List<String> TIER_SEVERITY = List.of(TIER_LOW, TIER_MODERATE, TIER_HIGH);

    /** 整餐聚合参数（由 meal_suitability_config 装配，引擎只提供算法中性默认值）。 */
    public record AggregationParams(BigDecimal shareThreshold, boolean skipUnknown) {
        public static AggregationParams defaults() {
            return new AggregationParams(BigDecimal.ZERO, true);
        }
    }

    /**
     * 评估整餐。
     *
     * @param rules       同一 rule_version 下的规则（允许乱序、含禁用行，本方法自行过滤排序）
     * @param items       食物项（应先经 MealNutritionResolver 归一化，含 per100gGL）；为空或 null 时整餐 UNKNOWN
     * @param adjustRules 份量修正规则（允许含禁用行）
     * @param params      整餐聚合参数（可空，取算法中性默认值）
     */
    public MealSuitabilityDtos.Evaluation evaluate(List<FoodSuitabilityRule> rules,
                                                   List<ItemInput> items,
                                                   List<MealPortionAdjustRule> adjustRules,
                                                   AggregationParams params) {
        List<FoodSuitabilityRule> ordered = orderRules(rules);
        List<MealPortionAdjustRule> orderedAdjust = orderAdjustRules(adjustRules);
        AggregationParams aggregation = params == null ? AggregationParams.defaults() : params;

        List<ItemResult> results = new ArrayList<>();
        List<ItemInput> validItems = new ArrayList<>();
        BigDecimal totalCarbs = BigDecimal.ZERO;
        if (items != null) {
            for (ItemInput item : items) {
                if (item == null) {
                    continue;
                }
                validItems.add(item);
                results.add(evaluateItem(ordered, orderedAdjust, item));
                if (item.getCarbsG() != null && item.getCarbsG().signum() > 0) {
                    totalCarbs = totalCarbs.add(item.getCarbsG());
                }
            }
        }

        MealSuitabilityDtos.Evaluation evaluation = new MealSuitabilityDtos.Evaluation();
        evaluation.setItems(results);
        evaluation.setOverallTier(TIER_UNKNOWN);
        evaluation.setWorstRuleCode(null);
        evaluation.setFocusItems(focusItemsOf(results));

        // 整餐：碳水占比最大的非 UNKNOWN 项（修正后档位）定档
        ItemResult driver = null;
        BigDecimal driverShare = BigDecimal.valueOf(-1);
        for (int i = 0; i < results.size(); i++) {
            ItemInput item = validItems.get(i);
            ItemResult result = results.get(i);
            if (item.getCarbsG() == null || item.getCarbsG().signum() <= 0 || totalCarbs.signum() <= 0) {
                continue; // carbsG 缺失项排除出占比分母与竞选
            }
            BigDecimal share = item.getCarbsG().divide(totalCarbs, 4, java.math.RoundingMode.HALF_UP);
            if (aggregation.shareThreshold() != null && share.compareTo(aggregation.shareThreshold()) < 0) {
                continue; // 占比低于门槛，不参与定档
            }
            if (TIER_UNKNOWN.equals(result.getTier())) {
                if (aggregation.skipUnknown()) {
                    continue; // UNKNOWN 跳过，取下一个占比项
                }
                break; // 不跳过策略下，占比最大项为 UNKNOWN → 整餐 UNKNOWN
            }
            if (share.compareTo(driverShare) > 0) {
                driver = result;
                driverShare = share;
            }
        }
        if (driver != null) {
            evaluation.setOverallTier(driver.getTier());
            evaluation.setWorstRuleCode(driver.getRuleCode());
        }
        return evaluation;
    }

    /** 单项评估：per100gGL 规则匹配 → 份量修正 → 重点关注标记。 */
    private ItemResult evaluateItem(List<FoodSuitabilityRule> ordered,
                                    List<MealPortionAdjustRule> orderedAdjust,
                                    ItemInput item) {
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
        result.setBaseTier(hit.getTier());
        result.setRuleCode(hit.getRuleCode());
        result.setTier(adjustTier(orderedAdjust, hit.getTier(), item.getCarbsG()));

        // 重点关注：最终档位 HIGH；原因区分「本身偏高」与「份量偏大」
        if (TIER_HIGH.equals(result.getTier())) {
            result.setFocused(true);
            result.setFocusReason(TIER_HIGH.equals(result.getBaseTier())
                    ? MealSuitabilityDtos.FOCUS_INTRINSIC : MealSuitabilityDtos.FOCUS_PORTION);
        }
        return result;
    }

    /** 份量修正：查 baseTier × carbsG 量级桶；无命中行则保持原档位。 */
    private String adjustTier(List<MealPortionAdjustRule> orderedAdjust, String baseTier, BigDecimal carbsG) {
        if (carbsG == null) {
            return baseTier; // 缺份量信息时不修正，保持 per100gGL 档位
        }
        for (MealPortionAdjustRule adjust : orderedAdjust) {
            if (!baseTier.equals(adjust.getBaseTier())) {
                continue;
            }
            if (adjust.getCarbBucketMin() != null && carbsG.compareTo(adjust.getCarbBucketMin()) < 0) {
                continue;
            }
            if (adjust.getCarbBucketMax() != null && carbsG.compareTo(adjust.getCarbBucketMax()) >= 0) {
                continue;
            }
            return adjust.getAdjustedTier();
        }
        return baseTier;
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
                continue; // MEAL 域规则不参与单项匹配（当前整餐为占比聚合，预留扩展）
            }
            ordered.add(rule);
        }
        ordered.sort(Comparator.comparingInt(
                (FoodSuitabilityRule rule) -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority()));
        return ordered;
    }

    private List<MealPortionAdjustRule> orderAdjustRules(List<MealPortionAdjustRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<MealPortionAdjustRule> ordered = new ArrayList<>(rules.size());
        for (MealPortionAdjustRule rule : rules) {
            if (rule == null || rule.getIsEnabled() == null || rule.getIsEnabled() != 1) {
                continue;
            }
            if (!TIER_SEVERITY.contains(rule.getAdjustedTier()) || rule.getBaseTier() == null) {
                continue;
            }
            ordered.add(rule);
        }
        ordered.sort(Comparator.comparingInt(
                (MealPortionAdjustRule rule) -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority()));
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
    private boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
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
