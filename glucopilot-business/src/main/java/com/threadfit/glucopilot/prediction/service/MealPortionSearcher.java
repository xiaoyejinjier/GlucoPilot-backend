package com.threadfit.glucopilot.prediction.service;

import com.threadfit.glucopilot.prediction.dto.PredictionDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 离散份量搜索（方案 §5）：在候选份量档上按比例缩放可削减类目，应用主食保底克重后复算 planCarbs，
 * 再用 planCarbs 复算预测值并与分层目标比较，返回第一个可行档。打破「反解 target → 回代恒等于 target」的结构性缺陷。
 *
 * <p>削减规则（§5.2）：主食(1)/水果(4)/奶(5)/豆(6)/坚果(7)/饮料(8)/零食(9) 可削减；
 * 肉蛋(2)/蔬菜(3) 保量；主食类应用 stapleFloor 保底，floor 顶回使该档不可行时弃用继续向下。
 */
public final class MealPortionSearcher {

    private static final BigDecimal STEP = new BigDecimal("0.1");
    /** BELOW 向上搜索的上限档（110%~150%）。 */
    private static final BigDecimal MAX_UP = new BigDecimal("1.5");
    private static final int STAPLE = 1;
    private static final Set<Integer> PRESERVE = Set.of(2, 3);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private MealPortionSearcher() {
    }

    /** 搜索参数：把预测口径相关的量一次性传入，避免长参数列表。 */
    public record Params(BigDecimal baseCarbs, BigDecimal targetLower, BigDecimal targetUpper,
                         BigDecimal safetyMargin, BigDecimal minPortionRatio, BigDecimal stapleFloor,
                         BigDecimal minMealCarbs, boolean allowZeroCarb,
                         Function<BigDecimal, BigDecimal> predictor) {
    }

    /** 搜索结果：命中份量档、复算后 planCarbs、逐食物建议克重（null=保持原份量）。 */
    public record Result(BigDecimal portion, BigDecimal planCarbs, List<Reduction> reductions) {
    }

    public record Reduction(String foodName, BigDecimal suggestWeight) {
    }

    /**
     * 执行离散搜索。
     * <p>downward（ABOVE）：1.0 → 0.9 → … → minPortionRatio，取第一个 predicted + margin ≤ targetUpper 的档；
     * upward（BELOW）：1.0 → 1.1 → … → 1.5，取第一个 predicted − margin ≥ targetLower 的档；无可行解返回 null。
     */
    public static Result search(List<PredictionDtos.Item> items, Params p, boolean upward) {
        BigDecimal ratio = BigDecimal.ONE;
        while (true) {
            BigDecimal planCarbs = planCarbs(items, p.baseCarbs(), ratio, p.stapleFloor());
            if (!p.allowZeroCarb() && planCarbs.compareTo(p.minMealCarbs()) < 0) {
                planCarbs = p.minMealCarbs();
            }
            BigDecimal predicted = p.predictor().apply(planCarbs);
            boolean feasible = upward
                    ? predicted.subtract(p.safetyMargin()).compareTo(p.targetLower()) >= 0
                    : predicted.add(p.safetyMargin()).compareTo(p.targetUpper()) <= 0;
            if (feasible) {
                return new Result(ratio, planCarbs, reductions(items, ratio, p.stapleFloor()));
            }
            if (upward) {
                if (ratio.compareTo(MAX_UP) >= 0) {
                    break;
                }
                ratio = ratio.add(STEP);
            } else {
                if (ratio.compareTo(p.minPortionRatio()) <= 0) {
                    break;
                }
                ratio = ratio.subtract(STEP);
            }
        }
        return null;
    }

    /** 指定份量档下的整餐碳水（planCarbs）：无明细则整体等比；有明细则按逐食物复算。 */
    private static BigDecimal planCarbs(List<PredictionDtos.Item> items, BigDecimal baseCarbs,
                                        BigDecimal ratio, BigDecimal stapleFloor) {
        if (items == null || items.isEmpty()) {
            return baseCarbs.multiply(ratio);
        }
        BigDecimal sum = ZERO;
        for (PredictionDtos.Item it : items) {
            sum = sum.add(reducedCarbsOf(it, ratio, stapleFloor));
        }
        return sum;
    }

    /** 逐食物复算该档下的碳水：保量类不变；主食应用保底克重；其余可削减类等比。 */
    private static BigDecimal reducedCarbsOf(PredictionDtos.Item it, BigDecimal ratio, BigDecimal stapleFloor) {
        int c = it.getCategoryCode();
        BigDecimal carbs = orZero(it.getCarbsG());
        if (PRESERVE.contains(c)) {
            return carbs;
        }
        BigDecimal weight = orZero(it.getWeightG());
        if (c == STAPLE && weight.compareTo(ZERO) > 0 && weight.compareTo(stapleFloor) > 0) {
            BigDecimal reducedWeight = weight.multiply(ratio).max(stapleFloor);
            return carbs.multiply(reducedWeight).divide(weight, 6, RoundingMode.HALF_UP);
        }
        return carbs.multiply(ratio);
    }

    /** 命中档下的逐食物建议克重（与 planCarbs 同源；null=保持原份量）。 */
    private static List<Reduction> reductions(List<PredictionDtos.Item> items, BigDecimal ratio,
                                              BigDecimal stapleFloor) {
        List<Reduction> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (PredictionDtos.Item it : items) {
            int c = it.getCategoryCode();
            BigDecimal weight = orZero(it.getWeightG());
            BigDecimal suggestWeight = null;
            if (c == STAPLE && weight.compareTo(ZERO) > 0 && weight.compareTo(stapleFloor) > 0) {
                suggestWeight = weight.multiply(ratio).max(stapleFloor).setScale(0, RoundingMode.HALF_UP);
            } else if (!PRESERVE.contains(c) && weight.compareTo(ZERO) > 0) {
                suggestWeight = weight.multiply(ratio).setScale(0, RoundingMode.HALF_UP);
            }
            out.add(new Reduction(it.getFoodName(), suggestWeight));
        }
        return out;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? ZERO : v;
    }
}