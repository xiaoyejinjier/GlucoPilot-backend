package com.threadfit.glucopilot.prediction.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成血糖数据集入口测试（仅测试作用域，零生产侵入）：
 * <ul>
 *   <li>{@link #generateDatasetFiles()}：@Disabled 手动生成，产物写入 target/prediction-dataset/（seed 固定可随时重生，不进 git）</li>
 *   <li>{@link #datasetShapeGuard()}：常规运行，锁数据集形状（规模 / seed 确定性 / carbsG 自洽 / 标签边界 / 分类码范围）</li>
 * </ul>
 *
 * <p>v2 起移除原 {@code baselineMaeGuard}：该锁的教师模型按 v1 线性公式生成标签，
 * 与 v2（收缩项 + 校准 R）不同构，锁值已无意义；且其 1.036 的 MAE 源自合成集自洽，
 * 比真实队列（1.784）乐观约一倍。公式正确性现由 {@code PredictionServiceTest} 的
 * 手算断言保证，真实精度基线由 {@code tools/prediction-dataset/evaluate.py --fixed-params} 把关。
 */
class SyntheticDatasetTest {

    private static final Path OUTPUT_DIR = Paths.get("target", "prediction-dataset");

    /**
     * 手动生成入口：默认跳过，加 -Ddataset.generate=true 触发，
     * 产物写入 target/prediction-dataset/（CSV + JSONL + 评估报告），seed 固定可随时重生。
     */
    @Test
    @EnabledIfSystemProperty(named = "dataset.generate", matches = "true")
    void generateDatasetFiles() throws IOException {
        List<MealSample> samples = new SyntheticMealDataset().generate();
        PredictionDatasetEvaluator.EvalResult result = new PredictionDatasetEvaluator(samples).evaluate();

        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("dataset.csv"),
                SyntheticMealDataset.toCsv(samples), StandardCharsets.UTF_8);
        Files.writeString(OUTPUT_DIR.resolve("dataset.jsonl"),
                SyntheticMealDataset.toJsonl(samples), StandardCharsets.UTF_8);
        Files.writeString(OUTPUT_DIR.resolve("evaluation-report.md"),
                PredictionDatasetEvaluator.renderMarkdown(result), StandardCharsets.UTF_8);

        assertTrue(samples.size() > 4000, "数据集规模异常: " + samples.size());
        assertTrue(Double.isFinite(result.mae()) && result.mae() > 0, "评估结果异常: " + result.mae());
    }

    @Test
    void datasetShapeGuard() {
        List<MealSample> samples = new SyntheticMealDataset().generate();

        // 规模：5 类型 × 6 血糖点 × 6 碳水档 × 4 开关 × 7 重复
        assertEquals(5040, samples.size(), "数据集规模必须等于场景矩阵展开");

        // seed 确定性：同 seed 两次生成逐条一致（record 深比较）
        assertEquals(samples, new SyntheticMealDataset().generate(), "同 seed 生成的数据集不一致");

        for (MealSample s : samples) {
            // 契约自洽：carbsG 恒等于 items 碳水之和（BigDecimal 补差保证无浮点漂移）
            BigDecimal itemsSum = s.items().stream()
                    .map(MealSample.SampleItem::carbsG)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, s.carbsG().compareTo(itemsSum),
                    "样本 " + s.sampleId() + " carbsG 与明细之和不一致");

            // 标签边界：模拟血糖仪输出，恒在 [0.1, 39.9]
            assertTrue(s.postprandialMeasured().doubleValue() >= 0.1
                            && s.postprandialMeasured().doubleValue() <= 39.9,
                    "样本 " + s.sampleId() + " 标签越界: " + s.postprandialMeasured());

            // 分类码与特征范围
            for (MealSample.SampleItem it : s.items()) {
                assertTrue(it.categoryCode() >= 1 && it.categoryCode() <= 9,
                        "样本 " + s.sampleId() + " 分类码越界: " + it.categoryCode());
            }
            assertTrue(s.currentGlucose().doubleValue() > 0 && s.currentGlucose().doubleValue() < 40,
                    "样本 " + s.sampleId() + " 餐前血糖越界");
            assertTrue(!s.items().isEmpty(), "样本 " + s.sampleId() + " 明细为空");
        }
    }
}
