package com.threadfit.glucopilot.prediction.dataset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

/**
 * 合成餐后血糖数据集生成器（方案 A：纯合成，教师模型 + 场景矩阵，仅测试作用域）。
 *
 * <p>教师模型（比线上 PredictionService 的纯碳水线性公式更精细，避免调优自我印证）：
 * <pre>
 *   ΔG = carbsG × portion × R_true(type) × sens(user) × GI(items) + 药物(-0.5) + 运动(-0.3) + ε
 *   ε ~ N(0, 0.10 × ΔG)        —— 个体内变异 CV≈10%
 *   sens ~ N(1, 0.25) 截断 [0.5, 1.75] —— 个体间变异 CV≈25%
 *   标签 = clamp(餐前 + ΔG', 0.1, 39.9) 保留 2 位（模拟血糖仪精度）
 * </pre>
 *
 * <p>场景矩阵：5 糖尿病类型 × 6 餐前血糖点 × 6 碳水档 × 4 修正开关 = 720 格 × 7 重复 = 5040 条。
 * 虚拟用户 200 个（每类型 40 个），userId 与糖尿病类型恒定绑定。
 *
 * <p>确定性：固定 seed，任何两次生成都逐条一致；target/ 清理后可随时重生成，产物不进 git。
 */
public final class SyntheticMealDataset {

    /** 复现性锚点：改动此值等价于换一份数据集，须同步重锚 SyntheticDatasetTest 的基线 MAE。 */
    static final long SEED = 20261020L;

    private static final int TYPE_COUNT = 5;
    private static final int USERS_PER_TYPE = 40;
    private static final int REPEATS = 7;

    /** 教师模型真实响应系数（刻意略偏于线上 R：0.04/0.06/0.05/0.04/0.04），下标 = 类型 0-4。 */
    private static final double[] R_TRUE = {0.042, 0.063, 0.051, 0.041, 0.040};

    /** 分类码 1-9 的 GI 因子：饮料快、蛋白纤维慢；按碳水占比加权。 */
    private static final Map<Integer, Double> GI = Map.of(
            1, 1.00, 2, 0.60, 3, 0.50, 4, 1.05,
            5, 0.90, 6, 0.85, 7, 0.80, 8, 1.30, 9, 1.15);

    /** 分类码 1-9 的碳水密度（g 碳水 / g 食物），仅用于从碳水反推展示克重，不参与公式。 */
    private static final Map<Integer, Double> DENSITY = Map.of(
            1, 0.26, 2, 0.02, 3, 0.04, 4, 0.12,
            5, 0.05, 6, 0.10, 7, 0.20, 8, 0.11, 9, 0.50);

    /** 各分类的食物名词表（固定中文词，保证 CSV/JSONL 无需转义）。 */
    private static final Map<Integer, String[]> FOOD_WORDS = Map.of(
            1, new String[]{"米饭", "面条", "馒头"},
            2, new String[]{"鸡蛋", "红烧肉", "鸡胸肉"},
            3, new String[]{"青菜", "西兰花", "番茄"},
            4, new String[]{"苹果", "香蕉", "橙子"},
            5, new String[]{"牛奶", "酸奶", "奶酪"},
            6, new String[]{"豆腐", "豆浆", "豆干"},
            7, new String[]{"花生", "杏仁", "核桃"},
            8, new String[]{"可乐", "奶茶", "橙汁"},
            9, new String[]{"薯片", "饼干", "蛋糕"});

    private static final double[] GLUCOSE_GRID = {3.0, 5.5, 7.0, 9.0, 12.0, 16.0};
    private static final double[] CARBS_GRID = {0, 20, 45, 90, 150, 250};
    private static final double[] PORTIONS = {0.75, 1.0, 1.25};
    /** 修正开关组合：{药物, 运动}。 */
    private static final boolean[][] SWITCHES = {{false, false}, {true, false}, {false, true}, {true, true}};

    private final double[] sensByUser = new double[TYPE_COUNT * USERS_PER_TYPE];
    private final Random random;

    public SyntheticMealDataset() {
        this(SEED);
    }

    public SyntheticMealDataset(long seed) {
        this.random = new Random(seed);
        for (int i = 0; i < sensByUser.length; i++) {
            double s;
            do {
                s = 1.0 + 0.25 * random.nextGaussian();
            } while (s < 0.5 || s > 1.75);
            sensByUser[i] = s;
        }
    }

    /** 生成全量数据集（同 seed 结果逐条一致）。 */
    public List<MealSample> generate() {
        List<MealSample> samples = new ArrayList<>();
        int sampleId = 0;
        for (int type = 0; type < TYPE_COUNT; type++) {
            for (double glucose : GLUCOSE_GRID) {
                for (double carbs : CARBS_GRID) {
                    for (boolean[] sw : SWITCHES) {
                        for (int rep = 0; rep < REPEATS; rep++) {
                            samples.add(sample(++sampleId, type, glucose, carbs, sw[0], sw[1]));
                        }
                    }
                }
            }
        }
        return samples;
    }

    private MealSample sample(int sampleId, int type, double glucose, double carbsTarget,
                              boolean medicationTaken, boolean exerciseDone) {
        // 虚拟用户：type 组内随机取一人，sens 与类型恒定绑定（评估器可按 userId 稳定建档）
        int idxInGroup = random.nextInt(USERS_PER_TYPE);
        long userId = (long) type * USERS_PER_TYPE + idxInGroup + 1;
        double sens = sensByUser[(int) userId - 1];
        double portion = PORTIONS[random.nextInt(PORTIONS.length)];

        List<MealSample.SampleItem> items = buildItems(carbsTarget);
        BigDecimal carbsG = items.stream()
                .map(MealSample.SampleItem::carbsG)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // GI 因子：碳水占比加权；全 0 碳水（纯蛋白/纤维餐）时取 1.0
        double gi = 1.0;
        double itemsCarbs = carbsG.doubleValue();
        if (itemsCarbs > 0) {
            double weighted = 0;
            for (MealSample.SampleItem it : items) {
                weighted += it.carbsG().doubleValue() * GI.get(it.categoryCode());
            }
            gi = weighted / itemsCarbs;
        }

        // 教师模型升幅与标签
        double delta = itemsCarbs * portion * R_TRUE[type] * sens * gi;
        double med = medicationTaken ? -0.5 : 0.0;
        double ex = exerciseDone ? -0.3 : 0.0;
        double noise = delta > 0 ? 0.10 * delta * random.nextGaussian() : 0.0;
        double label = clamp(glucose + delta + med + ex + noise, 0.1, 39.9);

        return new MealSample(sampleId, userId, type,
                bd(glucose, 1), carbsG, bd(portion, 2),
                medicationTaken, exerciseDone, items,
                bd(label, 2), bd(delta, 3), bd(gi, 3), bd(sens, 3));
    }

    /**
     * 构建食物明细，保证 Σ items.carbsG 与目标档位精确一致（BigDecimal 补差，无浮点漂移）。
     * 零碳水档：1-2 个零碳水类（肉蛋/蔬菜）；非零档：从 9 类中不放回抽 1-5 个按随机占比分摊。
     */
    private List<MealSample.SampleItem> buildItems(double carbsTarget) {
        if (carbsTarget == 0) {
            List<Integer> pool = new ArrayList<>(List.of(2, 3));
            Collections.shuffle(pool, random);
            int k = 1 + random.nextInt(pool.size());
            List<MealSample.SampleItem> items = new ArrayList<>(k);
            for (int cat : pool.subList(0, k)) {
                items.add(item(cat, BigDecimal.ZERO, bd(80 + random.nextInt(121), 0)));
            }
            return items;
        }

        List<Integer> pool = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
        Collections.shuffle(pool, random);
        int k = 1 + random.nextInt(5);
        List<Integer> cats = pool.subList(0, k);

        BigDecimal target = bd(carbsTarget, 1);
        double[] weights = new double[k];
        double sum = 0;
        for (int i = 0; i < k; i++) {
            weights[i] = 0.05 + random.nextDouble() * 0.95;
            sum += weights[i];
        }
        List<MealSample.SampleItem> items = new ArrayList<>(k);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < k; i++) {
            BigDecimal carbs;
            if (i == k - 1) {
                carbs = target.subtract(allocated);
            } else {
                carbs = bd(carbsTarget * weights[i] / sum, 1);
                allocated = allocated.add(carbs);
            }
            items.add(item(cats.get(i), carbs, weightOf(cats.get(i), carbs)));
        }
        return items;
    }

    private MealSample.SampleItem item(int category, BigDecimal carbsG, BigDecimal weightG) {
        String[] words = FOOD_WORDS.get(category);
        return new MealSample.SampleItem(words[random.nextInt(words.length)], category, weightG, carbsG);
    }

    /** 由碳水密度反推展示克重；密度极低的类可能给出夸张克重（如纯蔬菜凑 250g 碳水），不影响公式与评估。 */
    private BigDecimal weightOf(int category, BigDecimal carbsG) {
        double weight = carbsG.doubleValue() / DENSITY.get(category);
        return bd(weight, 1);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static BigDecimal bd(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    /** 序列化为 CSV（扁平特征 + 标签 + 诊断列；items 压缩为单列，食物名词表固定无逗号，无需转义）。 */
    public static String toCsv(List<MealSample> samples) {
        StringJoiner csv = new StringJoiner("\n");
        csv.add("sampleId,virtualUserId,diabetesType,currentGlucose,carbsG,portionRatio,"
                + "medicationTaken,exerciseDone,items,postprandialMeasured,teacherDelta,giFactor,sens");
        for (MealSample s : samples) {
            StringJoiner itemJoiner = new StringJoiner("|");
            for (MealSample.SampleItem it : s.items()) {
                itemJoiner.add(it.foodName() + "(" + it.categoryCode() + ")"
                        + it.weightG().toPlainString() + "g/" + it.carbsG().toPlainString() + "c");
            }
            csv.add(String.join(",",
                    String.valueOf(s.sampleId()),
                    String.valueOf(s.virtualUserId()),
                    String.valueOf(s.diabetesType()),
                    s.currentGlucose().toPlainString(),
                    s.carbsG().toPlainString(),
                    s.portionRatio().toPlainString(),
                    String.valueOf(s.medicationTaken()),
                    String.valueOf(s.exerciseDone()),
                    itemJoiner.toString(),
                    s.postprandialMeasured().toPlainString(),
                    s.teacherDelta().toPlainString(),
                    s.giFactor().toPlainString(),
                    s.sens().toPlainString()));
        }
        return csv.toString() + "\n";
    }

    /** 序列化为 JSONL（items 完整嵌套；值均为数字/布尔/固定中文词，无需转义处理）。 */
    public static String toJsonl(List<MealSample> samples) {
        StringBuilder sb = new StringBuilder();
        for (MealSample s : samples) {
            sb.append("{\"sampleId\":").append(s.sampleId())
                    .append(",\"virtualUserId\":").append(s.virtualUserId())
                    .append(",\"diabetesType\":").append(s.diabetesType())
                    .append(",\"currentGlucose\":").append(s.currentGlucose().toPlainString())
                    .append(",\"carbsG\":").append(s.carbsG().toPlainString())
                    .append(",\"portionRatio\":").append(s.portionRatio().toPlainString())
                    .append(",\"medicationTaken\":").append(s.medicationTaken())
                    .append(",\"exerciseDone\":").append(s.exerciseDone())
                    .append(",\"items\":[");
            StringJoiner items = new StringJoiner(",");
            for (MealSample.SampleItem it : s.items()) {
                items.add("{\"foodName\":\"" + it.foodName()
                        + "\",\"categoryCode\":" + it.categoryCode()
                        + ",\"weightG\":" + it.weightG().toPlainString()
                        + ",\"carbsG\":" + it.carbsG().toPlainString() + "}");
            }
            sb.append(items).append("],\"postprandialMeasured\":").append(s.postprandialMeasured().toPlainString())
                    .append(",\"teacherDelta\":").append(s.teacherDelta().toPlainString())
                    .append(",\"giFactor\":").append(s.giFactor().toPlainString())
                    .append(",\"sens\":").append(s.sens().toPlainString())
                    .append("}\n");
        }
        return sb.toString();
    }
}
