package com.threadfit.glucopilot.prediction.dataset;

import com.threadfit.glucopilot.prediction.config.PredictionProperties;
import com.threadfit.glucopilot.prediction.dto.PredictionDtos;
import com.threadfit.glucopilot.prediction.service.PredictionService;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用真实 {@link PredictionService}（v1 默认参数）对合成数据集逐条预测并统计误差，仅测试作用域。
 *
 * <p>评估对象为 predictNow（现状预测）；「临床可接受」采用简化 Clarke A 区规则：
 * 双低（预测与实测均 < 3.9）可接受；双高（均 > 16.7）误差 ≤ 2.0 可接受；
 * 实测在 3.9-16.7 正常带时 |误差| ≤ 20% × 实测 可接受；跨带即不可接受。
 */
public final class PredictionDatasetEvaluator {

    /** 个体化用药/运动修正：教师模型与线上同构（这部分机制两模型保持一致）。 */
    private static final double MED_EFFECT = -0.5;
    private static final double EX_EFFECT = -0.3;

    private final PredictionService service;
    private final List<MealSample> samples;

    /** 使用 v1 默认参数评估。 */
    public PredictionDatasetEvaluator(List<MealSample> samples) {
        this(samples, new PredictionProperties());
    }

    /** 使用给定参数评估（供参数寻优在候选 R 上复用同一套统计口径）。 */
    public PredictionDatasetEvaluator(List<MealSample> samples, PredictionProperties properties) {
        this.samples = samples;
        Map<Long, Integer> typeByUser = new HashMap<>();
        for (MealSample s : samples) {
            typeByUser.put(s.virtualUserId(), s.diabetesType());
        }
        // 手写只读 stub：虚拟用户的糖尿病类型在数据集内恒定绑定，无需 Mockito 交互
        HealthProfileService health = new HealthProfileService() {
            @Override
            public HealthView healthViewOf(Long userId) {
                return HealthView.builder().diabetesType(typeByUser.get(userId)).build();
            }

            @Override
            public HealthView saveHealth(Long userId, HealthSaveRequest request) {
                throw new UnsupportedOperationException("评估器只读健康档案");
            }

            @Override
            public void purgeAccountData(Long userId) {
                throw new UnsupportedOperationException("评估器只读健康档案，不参与注销清理");
            }
        };
        this.service = new PredictionService(properties, health);
    }

    /** 分层统计：层名、样本数、MAE、临床可接受率。 */
    public record LayerStat(String layer, int n, double mae, double acceptableRate) {
    }

    /** 总体 + 分层评估结果。 */
    public record EvalResult(
            int n,
            double mae,
            double rmse,
            double maxAbsError,
            double acceptableRate,
            List<LayerStat> byType,
            List<LayerStat> byGlucose,
            List<LayerStat> byCarbs) {
    }

    /** 逐条预测并统计。误差 = predictNow − 教师模型标签（两者均已在 [0.1, 39.9] 内）。 */
    public EvalResult evaluate() {
        List<double[]> rows = new ArrayList<>(samples.size()); // {pred, ref, type, glucose, carbs}
        for (MealSample s : samples) {
            PredictionDtos.PredictRequest req = new PredictionDtos.PredictRequest();
            req.setNodeCode("lunch");
            req.setCurrentGlucose(s.currentGlucose());
            req.setCarbsG(s.carbsG());
            req.setPortionRatio(s.portionRatio());
            req.setMedicationTaken(s.medicationTaken());
            req.setExerciseDone(s.exerciseDone());
            req.setItems(s.items().stream().map(it -> {
                PredictionDtos.Item item = new PredictionDtos.Item();
                item.setFoodName(it.foodName());
                item.setCategoryCode(it.categoryCode());
                item.setWeightG(it.weightG());
                item.setCarbsG(it.carbsG());
                return item;
            }).toList());
            double pred = service.predict(s.virtualUserId(), req).getPredictNow().doubleValue();
            rows.add(new double[]{pred, s.postprandialMeasured().doubleValue(),
                    s.diabetesType(), s.currentGlucose().doubleValue(), s.carbsG().doubleValue()});
        }

        double sumAbs = 0;
        double sumSq = 0;
        double maxAbs = 0;
        int acceptable = 0;
        for (double[] r : rows) {
            double err = Math.abs(r[0] - r[1]);
            sumAbs += err;
            sumSq += err * err;
            maxAbs = Math.max(maxAbs, err);
            if (clinicallyAcceptable(r[0], r[1])) {
                acceptable++;
            }
        }
        int n = rows.size();
        return new EvalResult(
                n, sumAbs / n, Math.sqrt(sumSq / n), maxAbs, (double) acceptable / n,
                layerOf(rows, r -> String.valueOf((int) r[2])),
                layerOf(rows, r -> glucoseBucket(r[3])),
                layerOf(rows, r -> carbsBucket(r[4])));
    }

    /** 按样本特征分层统计，结果按层名排序输出。 */
    private static List<LayerStat> layerOf(List<double[]> rows,
                                           java.util.function.Function<double[], String> keyFn) {
        Map<String, List<double[]>> groups = new HashMap<>();
        for (double[] r : rows) {
            groups.computeIfAbsent(keyFn.apply(r), k -> new ArrayList<>()).add(r);
        }
        return groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<double[]> g = e.getValue();
                    double sumAbs = 0;
                    int acc = 0;
                    for (double[] r : g) {
                        sumAbs += Math.abs(r[0] - r[1]);
                        if (clinicallyAcceptable(r[0], r[1])) {
                            acc++;
                        }
                    }
                    return new LayerStat(e.getKey(), g.size(), sumAbs / g.size(), (double) acc / g.size());
                })
                .toList();
    }

    /** 简化 Clarke A 区：双低可接受；双高误差 ≤ 2.0；正常带 ±20%；跨带不可接受。 */
    private static boolean clinicallyAcceptable(double pred, double ref) {
        if (pred < 3.9 && ref < 3.9) {
            return true;
        }
        if (pred > 16.7 && ref > 16.7) {
            return Math.abs(pred - ref) <= 2.0;
        }
        if (ref >= 3.9 && ref <= 16.7) {
            return Math.abs(pred - ref) <= 0.20 * ref;
        }
        return false;
    }

    private static String glucoseBucket(double glucose) {
        if (glucose <= 5.5) {
            return "低(≤5.5)";
        }
        if (glucose <= 9.0) {
            return "中(7-9)";
        }
        return "高(≥12)";
    }

    private static String carbsBucket(double carbs) {
        if (carbs == 0) {
            return "0";
        }
        if (carbs <= 45) {
            return "低(≤45)";
        }
        if (carbs <= 90) {
            return "中(90)";
        }
        return "高(≥150)";
    }

    /** 渲染 Markdown 评估报告（写入 target/prediction-dataset/evaluation-report.md）。 */
    public static String renderMarkdown(EvalResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 合成数据集预测评估报告\n\n");
        sb.append("- 算法版本：v1（PredictionProperties 默认参数）\n");
        sb.append("- 数据集：SyntheticMealDataset（seed=").append(SyntheticMealDataset.SEED).append("）\n");
        sb.append(String.format("- 总样本：%d 条%n", r.n()));
        sb.append("\n## 总体指标\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append(String.format("| MAE (mmol/L) | %.3f |%n", r.mae()));
        sb.append(String.format("| RMSE (mmol/L) | %.3f |%n", r.rmse()));
        sb.append(String.format("| 最大绝对误差 | %.3f |%n", r.maxAbsError()));
        sb.append(String.format("| 临床可接受率（简化 Clarke A 区） | %.1f%% |%n", r.acceptableRate() * 100));
        sb.append("\n## 按糖尿病类型分层\n\n");
        appendLayer(sb, r.byType());
        sb.append("\n## 按餐前血糖分层\n\n");
        appendLayer(sb, r.byGlucose());
        sb.append("\n## 按碳水档分层\n\n");
        appendLayer(sb, r.byCarbs());
        sb.append("\n## 口径说明\n\n");
        sb.append("- 误差 = predictNow − 教师模型标签；教师模型含 GI 因子与个体差异，");
        sb.append("线上公式无此机制，故 MAE 含不可解释方差（模拟真实世界观测误差）。\n");
        sb.append("- 药物/运动修正两模型同构（").append(MED_EFFECT).append("/").append(EX_EFFECT).append("），");
        sb.append("该部分不贡献系统偏差。\n");
        sb.append("- 基线锁见 SyntheticDatasetTest#baselineMaeGuard：参数改动不得使 MAE 劣化。\n");
        return sb.toString();
    }

    private static void appendLayer(StringBuilder sb, List<LayerStat> stats) {
        sb.append("| 层 | 样本数 | MAE | 可接受率 |\n|---|---|---|---|\n");
        for (LayerStat s : stats) {
            sb.append(String.format("| %s | %d | %.3f | %.1f%% |%n",
                    s.layer(), s.n(), s.mae(), s.acceptableRate() * 100));
        }
    }
}
