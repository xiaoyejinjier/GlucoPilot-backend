package com.threadfit.glucopilot.ai.tool.support;

import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 血糖「达标」口径的唯一实现处（工具层）。
 *
 * <p><b>为什么需要单独一个类</b>：达标率是模型最容易算错、用户最容易被误导的数字。
 * 之前 {@code get_month_stats} 把「有记录天数 / 当月天数」叫作 complianceRate，
 * 模型看到「达标率」三个字就会对用户说「你本月达标率 0.71」——那其实是打卡覆盖率。
 * 口径一旦散落在各个工具里，这种错就会反复出现。所以此处定一次，所有工具引用它。
 *
 * <p><b>口径</b>（与国内指南一致，且只用用户自己填过的目标值）：
 *
 * <ul>
 *   <li>偏低：{@code value < 3.9}（低血糖界值，与目标值无关，始终生效）；
 *   <li>达标：{@code 3.9 <= value <= 目标值}，餐点时段用「餐后目标」，其余用「空腹目标」；
 *   <li>偏高：{@code value > 目标值}。
 * </ul>
 *
 * <p>用户没填目标值时用 6.1 / 7.8 兜底，并在返回里标 {@code fromProfile=false}——
 * 让模型在回答里说清「按通用目标」，而不是把自己的假设当作用户的医嘱。
 */
public final class GlucoseRates {

    /** 低血糖界值（mmol/L）。 */
    public static final BigDecimal LOW_LIMIT = new BigDecimal("3.9");

    /** 档案缺失时的兜底空腹目标。 */
    public static final BigDecimal DEFAULT_FASTING_TARGET = new BigDecimal("6.1");

    /** 档案缺失时的兜底餐后目标。 */
    public static final BigDecimal DEFAULT_POSTPRANDIAL_TARGET = new BigDecimal("7.8");

    private static final int SCALE = 2;

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    private GlucoseRates() {}

    /**
     * 本次统计实际使用的目标值。
     *
     * @param fromProfile 是否来自用户健康档案（false 表示用了兜底值）
     */
    public record Targets(BigDecimal fasting, BigDecimal postprandial, boolean fromProfile) {

        /** 某个时段该用哪个目标值。 */
        public BigDecimal forMeal(boolean meal) {
            return meal ? postprandial : fasting;
        }
    }

    /** 从健康档案解析目标值；档案为空/字段为空时逐项兜底（不回退到「没有目标」）。 */
    public static Targets targets(HealthView view) {
        BigDecimal fasting = view == null ? null : view.getTargetFasting();
        BigDecimal postprandial = view == null ? null : view.getTargetPostprandial();
        boolean fromProfile = fasting != null && postprandial != null;
        return new Targets(
                fasting == null ? DEFAULT_FASTING_TARGET : fasting,
                postprandial == null ? DEFAULT_POSTPRANDIAL_TARGET : postprandial,
                fromProfile);
    }

    /** 是否落在达标区间内：不低于低血糖界值，且不超过该时段目标值。 */
    public static boolean inRange(BigDecimal value, boolean meal, Targets targets) {
        if (value == null) {
            return false;
        }
        return value.compareTo(LOW_LIMIT) >= 0 && value.compareTo(targets.forMeal(meal)) <= 0;
    }

    /** 聚合计数：一段区间内的达标 / 偏高 / 偏低条数。 */
    public record Tally(long total, long inRange, long high, long low) {

        public BigDecimal inRangeRate() {
            return rate(inRange, total);
        }

        public BigDecimal highRate() {
            return rate(high, total);
        }

        public BigDecimal lowRate() {
            return rate(low, total);
        }
    }

    /** 按达标口径统计一批数值（{@code mealFlags} 与 {@code values} 等长）。 */
    public static Tally tally(List<BigDecimal> values, List<Boolean> mealFlags, Targets targets) {
        long total = 0;
        long inRange = 0;
        long high = 0;
        long low = 0;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            if (value == null) {
                continue;
            }
            total++;
            boolean meal = i < mealFlags.size() && Boolean.TRUE.equals(mealFlags.get(i));
            if (value.compareTo(LOW_LIMIT) < 0) {
                low++;
            } else if (value.compareTo(targets.forMeal(meal)) > 0) {
                high++;
            } else {
                inRange++;
            }
        }
        return new Tally(total, inRange, high, low);
    }

    /** 比率：分母为 0 时返回 0，保留两位小数（0~1 之间的小数，不是百分数）。 */
    public static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    /** 算术平均；空集返回 null（让调用方决定「没有数据」怎么表达，而不是返回 0 假装有值）。 */
    public static BigDecimal mean(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        BigDecimal sum = present.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(present.size()), SCALE, RoundingMode.HALF_UP);
    }

    /** 变异系数（标准差 / 平均），衡量波动；样本不足 2 条或均值为 0 时返回 null。 */
    public static BigDecimal coefficientOfVariation(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.size() < 2) {
            return null;
        }
        BigDecimal mean = mean(present);
        if (mean == null || mean.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal variance =
                present.stream()
                        .map(v -> v.subtract(mean).pow(2))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(present.size()), MC);
        BigDecimal sd = variance.sqrt(MC);
        return sd.divide(mean, SCALE, RoundingMode.HALF_UP);
    }
}
