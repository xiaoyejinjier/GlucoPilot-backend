package com.threadfit.glucopilot.prediction.dataset;

import com.threadfit.glucopilot.prediction.config.PredictionProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 预测参数寻优器（仅测试作用域，不改生产代码）。
 *
 * <p>依据：线上公式与教师模型都按糖尿病类型取响应系数，某类型的 R 只影响该类型样本，
 * 因此无需 5 维网格搜索，逐类型在自身子集上做一维扫描即可（0.030–0.080，步长 0.001）。
 *
 * <p>目标函数：类型子集 MAE 最小。注意最优 R 是「有效响应系数」，吸收了教师模型中
 * GI 因子与个体敏感系数的均值效应，不等同于教师模型写入的 R_true。
 */
public final class PredictionParameterTuner {

    private static final double R_MIN = 0.030;
    private static final double R_MAX = 0.080;
    private static final double R_STEP = 0.001;
    private static final int TYPE_COUNT = 5;

    private final List<MealSample> samples;

    public PredictionParameterTuner(List<MealSample> samples) {
        this.samples = samples;
    }

    /** 单个类型的最优参数与前后对比。 */
    public record TypeTune(int type, int n,
                           double currentR, double currentMae, double currentAcceptRate,
                           double bestR, double bestMae, double bestAcceptRate) {
    }

    /** 寻优结果：逐类型明细 + 全量集上的前后总体指标 + 建议参数集。 */
    public record TuneResult(List<TypeTune> byType,
                             double overallMaeBefore, double overallMaeAfter,
                             double overallAcceptBefore, double overallAcceptAfter,
                             PredictionProperties bestProperties) {
    }

    public TuneResult tune() {
        List<TypeTune> byType = new ArrayList<>(TYPE_COUNT);
        PredictionProperties best = new PredictionProperties();

        for (int type = 0; type < TYPE_COUNT; type++) {
            final int currentType = type;
            List<MealSample> subset = samples.stream()
                    .filter(s -> s.diabetesType() == currentType)
                    .toList();

            PredictionProperties baseline = new PredictionProperties();
            double currentR = rOf(baseline, type);
            PredictionDatasetEvaluator.EvalResult base =
                    new PredictionDatasetEvaluator(subset, baseline).evaluate();

            double bestR = currentR;
            double bestMae = base.mae();
            double bestAccept = base.acceptableRate();

            int steps = (int) Math.round((R_MAX - R_MIN) / R_STEP);
            for (int i = 0; i <= steps; i++) {
                double candidate = round3(R_MIN + i * R_STEP);
                PredictionProperties props = new PredictionProperties();
                setR(props, type, candidate);
                PredictionDatasetEvaluator.EvalResult r =
                        new PredictionDatasetEvaluator(subset, props).evaluate();
                if (r.mae() < bestMae) {
                    bestMae = r.mae();
                    bestR = candidate;
                    bestAccept = r.acceptableRate();
                }
            }

            setR(best, type, bestR);
            byType.add(new TypeTune(type, subset.size(), currentR, base.mae(), base.acceptableRate(),
                    bestR, bestMae, bestAccept));
        }

        PredictionDatasetEvaluator.EvalResult before =
                new PredictionDatasetEvaluator(samples, new PredictionProperties()).evaluate();
        PredictionDatasetEvaluator.EvalResult after =
                new PredictionDatasetEvaluator(samples, best).evaluate();

        return new TuneResult(byType,
                before.mae(), after.mae(),
                before.acceptableRate(), after.acceptableRate(),
                best);
    }

    private static double rOf(PredictionProperties p, int type) {
        return switch (type) {
            case 0 -> p.getRType0().doubleValue();
            case 1 -> p.getRType1().doubleValue();
            case 2 -> p.getRType2().doubleValue();
            case 3 -> p.getRType3().doubleValue();
            case 4 -> p.getRType4().doubleValue();
            default -> throw new IllegalArgumentException("未知糖尿病类型: " + type);
        };
    }

    private static void setR(PredictionProperties p, int type, double r) {
        BigDecimal v = BigDecimal.valueOf(r).setScale(3, RoundingMode.HALF_UP);
        switch (type) {
            case 0 -> p.setRType0(v);
            case 1 -> p.setRType1(v);
            case 2 -> p.setRType2(v);
            case 3 -> p.setRType3(v);
            case 4 -> p.setRType4(v);
            default -> throw new IllegalArgumentException("未知糖尿病类型: " + type);
        }
    }

    private static double round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    /** 渲染 Markdown 寻优报告（写入 target/prediction-dataset/tuning-report.md）。 */
    public static String renderMarkdown(TuneResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 预测参数寻优报告\n\n");
        sb.append("- 数据集：SyntheticMealDataset（seed=").append(SyntheticMealDataset.SEED).append("）\n");
        sb.append("- 目标函数：按糖尿病类型子集的 predictNow MAE 最小\n");
        sb.append("- 搜索范围：R ∈ [").append(R_MIN).append(", ").append(R_MAX)
                .append("]，步长 ").append(R_STEP).append("\n\n");

        sb.append("## 逐类型结果\n\n");
        sb.append("| 类型 | 样本 | 当前R | 当前MAE | 最优R | 最优MAE | MAE改善 | 可接受率 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (TypeTune t : result.byType()) {
            sb.append(String.format("| %d | %d | %.3f | %.3f | %.3f | %.3f | %+.3f | %.1f%% → %.1f%% |%n",
                    t.type(), t.n(), t.currentR(), t.currentMae(), t.bestR(), t.bestMae(),
                    t.bestMae() - t.currentMae(), t.currentAcceptRate() * 100, t.bestAcceptRate() * 100));
        }

        sb.append("\n## 全量集前后对比\n\n");
        sb.append("| 指标 | 当前参数 | 寻优后 | 变化 |\n|---|---|---|---|\n");
        sb.append(String.format("| MAE (mmol/L) | %.3f | %.3f | %+.3f |%n",
                result.overallMaeBefore(), result.overallMaeAfter(),
                result.overallMaeAfter() - result.overallMaeBefore()));
        sb.append(String.format("| 临床可接受率 | %.1f%% | %.1f%% | %+.1f pt |%n",
                result.overallAcceptBefore() * 100, result.overallAcceptAfter() * 100,
                (result.overallAcceptAfter() - result.overallAcceptBefore()) * 100));

        sb.append("\n## 建议参数（供 PredictionProperties 参考）\n\n");
        sb.append("```properties\n");
        PredictionProperties p = result.bestProperties();
        sb.append("glucopilot.prediction.r-type-0=").append(p.getRType0().toPlainString()).append("\n");
        sb.append("glucopilot.prediction.r-type-1=").append(p.getRType1().toPlainString()).append("\n");
        sb.append("glucopilot.prediction.r-type-2=").append(p.getRType2().toPlainString()).append("\n");
        sb.append("glucopilot.prediction.r-type-3=").append(p.getRType3().toPlainString()).append("\n");
        sb.append("glucopilot.prediction.r-type-4=").append(p.getRType4().toPlainString()).append("\n");
        sb.append("```\n\n");

        sb.append("## 口径说明与风险\n\n");
        sb.append("- 最优 R 是「有效响应系数」：吸收了教师模型中 GI 因子与个体敏感系数的均值效应，")
                .append("因此与 R_true 不等且不应对二者做数值对比。\n");
        sb.append("- 本次寻优只能在合成集上消除系统偏移，**不构成临床验证**；真实人群的 GI 构成、")
                .append("胰岛素分泌能力差异未被建模，直接上线前须以真实数据复核。\n");
        sb.append("- 碳水 0 档误差恒为 0（药物/运动两模型同构），故 MAE 下界由 GI 因子与个体差异决定，")
                .append("寻优只能逼近、不能归零。\n");
        sb.append("- 若采纳新参数：须同步重锚 SyntheticDatasetTest#BASELINE_MAE，并复核 PredictionServiceTest 的期望值，")
                .append("以及 sys_config 中可能存在的同源阈值（防漂移）。\n");
        return sb.toString();
    }
}
