package com.threadfit.glucopilot.prediction.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.prediction.config.PredictionProperties;
import com.threadfit.glucopilot.prediction.dto.PredictionDtos;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 餐后血糖预测（纯计算，不落库、不查血糖/饮食记录）。
 * 唯一服务端读 = {@link HealthProfileService#healthViewOf}；算法参数见 {@link PredictionProperties}。
 *
 * <p>P3 起：建议份量由 {@link MealPortionSearcher} 离散搜索 + planCarbs 复算得到，
 * 收缩锚点恒为 7.8（与分层目标解耦），不再反解目标碳水（消除 predictSuggest ≡ target 的恒等式）。
 */
@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final String ALGORITHM_VERSION = "v2.1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal GLUCOSE_MIN = new BigDecimal("0.1");
    private static final BigDecimal GLUCOSE_MAX = new BigDecimal("39.9");
    private static final int DIVIDE_SCALE = 6;
    /** 低血糖优先阈值（mmol/L）；cur 低于此值走 LOW，优先处理低血糖。 */
    private static final BigDecimal LOW_THRESHOLD = new BigDecimal("3.9");

    private final PredictionProperties props;
    private final HealthProfileService healthProfileService;

    /** 建议产出：文案与结构化数据同源派生，保证强一致。 */
    private record Advices(String text, List<PredictionDtos.SuggestItem> items) {}

    /** 推荐状态枚举。 */
    private enum RecommendationStatus { IN_RANGE, ABOVE, BELOW, LOW, DANGER, INFEASIBLE }

    /** 入口：先校验 nodeCode 必须为餐点节点；唯一服务端依赖 = 读健康档案。未建档时 HealthView 为空对象，分类参数取默认，不会报错。 */
    public PredictionDtos.PredictResult predict(Long userId, PredictionDtos.PredictRequest request) {
        if (!GlucoseNodes.isMealNode(request.getNodeCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "nodeCode 必须为餐点节点");
        }
        HealthView health = healthProfileService.healthViewOf(userId);
        return compute(request, health);
    }

    /**
     * 核心：①按当前量预测 predictNow；②状态机判定；③ABOVE/BELOW 走离散搜索得推荐份量并复算 predictSuggest。
     */
    private PredictionDtos.PredictResult compute(PredictionDtos.PredictRequest req, HealthView health) {
        BigDecimal cur = req.getCurrentGlucose();
        BigDecimal carbs = req.getCarbsG();
        BigDecimal portion = req.getPortionRatio() == null ? ONE : req.getPortionRatio();
        boolean med = Boolean.TRUE.equals(req.getMedicationTaken());
        boolean ex = Boolean.TRUE.equals(req.getExerciseDone());

        BigDecimal r = rFor(health.getDiabetesType());
        BigDecimal dMed = med ? props.getMedEffect() : ZERO;
        BigDecimal dEx = ex ? props.getExerciseEffect() : ZERO;

        // R1：收缩锚点恒为 defaultTarget（7.8），与分层目标解耦；不再读用户 targetPostprandial/Fasting。
        BigDecimal dShrink = props.getShrinkK().multiply(props.getDefaultTarget().subtract(cur));
        BigDecimal predictNow = roundGlucose(cur.add(dShrink).add(carbs.multiply(portion).multiply(r)).add(dMed).add(dEx));

        GlucoseGoalResolver.ResolvedGoal goal = GlucoseGoalResolver.resolve(health);
        BigDecimal safetyMargin = goal.effectiveProfile() == GlucoseGoalResolver.Profile.GDM
                ? props.getSafetyMarginGdm() : props.getSafetyMargin();
        boolean allowZeroCarb = goal.effectiveProfile() != GlucoseGoalResolver.Profile.GDM
                && goal.effectiveProfile() != GlucoseGoalResolver.Profile.OLDER_HYPOGLYCEMIA;

        Function<BigDecimal, BigDecimal> predictor = planCarbs -> roundGlucose(
                cur.add(dShrink).add(planCarbs.multiply(r)).add(dMed).add(dEx));

        RecommendationStatus base = classify(cur, predictNow, goal);

        BigDecimal suggestCarbs;
        BigDecimal predictSuggest;
        Advices advices;
        RecommendationStatus finalStatus;
        List<String> hints = new ArrayList<>();

        switch (base) {
            case DANGER -> {
                suggestCarbs = ZERO;
                predictSuggest = null;
                advices = new Advices("当前血糖明显偏高，建议暂缓进食并尽快就医", List.of());
                finalStatus = RecommendationStatus.DANGER;
                hints.add("danger_high");
            }
            case LOW -> {
                // 低血糖优先，不减不加量
                suggestCarbs = carbs.multiply(portion).setScale(1, RoundingMode.HALF_UP);
                predictSuggest = predictNow;
                advices = new Advices("当前血糖偏低，请先处理低血糖（如进食含糖食物），再录入本餐", List.of());
                finalStatus = RecommendationStatus.LOW;
                hints.add("low_first");
            }
            case IN_RANGE -> {
                suggestCarbs = carbs.multiply(portion).setScale(1, RoundingMode.HALF_UP);
                predictSuggest = predictNow;
                advices = new Advices("当前份量预计血糖在目标范围内，无需调整", preserveItems(req.getItems()));
                finalStatus = RecommendationStatus.IN_RANGE;
            }
            case ABOVE -> {
                MealPortionSearcher.Result sr = search(req.getItems(), carbs, goal, safetyMargin, allowZeroCarb, predictor, false);
                if (sr == null) {
                    suggestCarbs = null;
                    predictSuggest = null;
                    advices = new Advices(infeasibleText(goal), List.of());
                    finalStatus = RecommendationStatus.INFEASIBLE;
                    hints.add(infeasibleHint(goal));
                } else {
                    suggestCarbs = sr.planCarbs().setScale(1, RoundingMode.HALF_UP);
                    predictSuggest = predictor.apply(sr.planCarbs());
                    advices = ratioAdvices(sr.reductions());
                    finalStatus = RecommendationStatus.ABOVE;
                    hints.add("above_adjusted");
                }
            }
            case BELOW -> {
                MealPortionSearcher.Result sr = search(req.getItems(), carbs, goal, safetyMargin, allowZeroCarb, predictor, true);
                if (sr == null) {
                    suggestCarbs = null;
                    predictSuggest = null;
                    advices = new Advices(infeasibleText(goal), List.of());
                    finalStatus = RecommendationStatus.INFEASIBLE;
                    hints.add(infeasibleHint(goal));
                } else {
                    suggestCarbs = sr.planCarbs().setScale(1, RoundingMode.HALF_UP);
                    predictSuggest = predictor.apply(sr.planCarbs());
                    advices = ratioAdvices(sr.reductions());
                    finalStatus = RecommendationStatus.BELOW;
                    hints.add("below_add");
                }
            }
            default -> throw new IllegalStateException("unexpected status " + base);
        }

        boolean caution = finalStatus != RecommendationStatus.DANGER
                && cur.compareTo(props.getCautionHigh()) >= 0;
        if (caution) {
            hints.add("caution_high");
        }

        return PredictionDtos.PredictResult.builder()
                .predictNow(predictNow)
                .suggestText(advices.text())
                .predictSuggest(predictSuggest)
                .suggestCarbs(suggestCarbs)
                .suggestItems(advices.items())
                .algorithmVersion(ALGORITHM_VERSION)
                .recommendationStatus(finalStatus.name())
                .targetLower(goal.targetLower())
                .targetUpper(goal.targetUpper())
                .targetSource(goal.source().name())
                .effectiveProfile(goal.effectiveProfile().name())
                .caution(caution)
                .statusHints(hints)
                .build();
    }

    /** 状态机基础判定（§4.2）：DANGER → LOW → IN_RANGE → ABOVE → BELOW；INFEASIBLE 由搜索无解产生。 */
    private RecommendationStatus classify(BigDecimal cur, BigDecimal predictNow,
                                          GlucoseGoalResolver.ResolvedGoal goal) {
        if (cur.compareTo(props.getDangerHigh()) >= 0) {
            return RecommendationStatus.DANGER;
        }
        if (cur.compareTo(LOW_THRESHOLD) < 0) {
            return RecommendationStatus.LOW;
        }
        if (predictNow.compareTo(goal.targetLower()) >= 0 && predictNow.compareTo(goal.targetUpper()) <= 0) {
            return RecommendationStatus.IN_RANGE;
        }
        if (predictNow.compareTo(goal.targetUpper()) > 0) {
            return RecommendationStatus.ABOVE;
        }
        return RecommendationStatus.BELOW;
    }

    private MealPortionSearcher.Result search(List<PredictionDtos.Item> items, BigDecimal carbs,
                                              GlucoseGoalResolver.ResolvedGoal goal, BigDecimal safetyMargin,
                                              boolean allowZeroCarb, Function<BigDecimal, BigDecimal> predictor,
                                              boolean upward) {
        return MealPortionSearcher.search(items, new MealPortionSearcher.Params(
                carbs, goal.targetLower(), goal.targetUpper(), safetyMargin,
                props.getMinPortionRatio(), props.getStapleFloor(), props.getMinMealCarbs(),
                allowZeroCarb, predictor), upward);
    }

    private String infeasibleHint(GlucoseGoalResolver.ResolvedGoal goal) {
        return goal.effectiveProfile() == GlucoseGoalResolver.Profile.GDM ? "gdm_infeasible" : "infeasible_normal";
    }

    private String infeasibleText(GlucoseGoalResolver.ResolvedGoal goal) {
        return goal.effectiveProfile() == GlucoseGoalResolver.Profile.GDM
                ? "当前预测已超出妊娠期严格目标，请按产科/营养科方案进食并加强监测"
                : "已接近最低建议量，仍可能超标，建议调整食物结构或咨询营养师";
    }

    private List<PredictionDtos.SuggestItem> preserveItems(List<PredictionDtos.Item> items) {
        List<PredictionDtos.SuggestItem> out = new ArrayList<>();
        if (items != null) {
            for (PredictionDtos.Item it : items) {
                out.add(suggestOf(it.getFoodName(), null));
            }
        }
        return out;
    }

    /** 命中档下的逐食物建议文案与结构化建议（与 planCarbs 同源）。 */
    private Advices ratioAdvices(List<MealPortionSearcher.Reduction> reductions) {
        if (reductions.isEmpty()) {
            return new Advices("建议按推荐份量用餐", List.of());
        }
        StringBuilder sb = new StringBuilder();
        List<PredictionDtos.SuggestItem> list = new ArrayList<>();
        for (int i = 0; i < reductions.size(); i++) {
            MealPortionSearcher.Reduction red = reductions.get(i);
            if (i > 0) {
                sb.append(" · ");
            }
            if (red.suggestWeight() == null) {
                sb.append(red.foodName()).append(" 正常");
            } else {
                sb.append(red.foodName()).append(" ")
                        .append(red.suggestWeight().stripTrailingZeros().toPlainString()).append("g");
            }
            list.add(suggestOf(red.foodName(), red.suggestWeight()));
        }
        return new Advices(sb.toString(), list);
    }

    private static PredictionDtos.SuggestItem suggestOf(String foodName, BigDecimal suggestWeight) {
        return PredictionDtos.SuggestItem.builder()
                .foodName(foodName)
                .suggestWeight(suggestWeight)
                .build();
    }

    private BigDecimal rFor(Integer diabetesType) {
        int t = diabetesType == null ? 0 : diabetesType;
        return switch (t) {
            case 1 -> props.getRType1();
            case 2 -> props.getRType2();
            case 3 -> props.getRType3();
            case 4 -> props.getRType4();
            default -> props.getRType0();
        };
    }

    /**
     * 预测值边界处理：先 clamp 到 [0.1, 39.9]，再 setScale(1,HALF_UP) 四舍五入。
     * 顺序必须「先夹取再舍入」——若先舍入，如 39.96 会 HALF_UP 进位成 40.0，突破 39.9 上限。
     */
    private static BigDecimal roundGlucose(BigDecimal v) {
        return clamp(v, GLUCOSE_MIN, GLUCOSE_MAX).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v.compareTo(lo) < 0) {
            return lo;
        }
        if (v.compareTo(hi) > 0) {
            return hi;
        }
        return v;
    }
}