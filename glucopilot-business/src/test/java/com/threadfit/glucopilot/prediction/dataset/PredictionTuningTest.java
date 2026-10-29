package com.threadfit.glucopilot.prediction.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数寻优入口（仅测试作用域）：默认跳过，加 -Ddataset.tune=true 触发。
 * 产物写入 target/prediction-dataset/tuning-report.md。
 */
class PredictionTuningTest {

    private static final Path OUTPUT_DIR = Paths.get("target", "prediction-dataset");

    @Test
    @EnabledIfSystemProperty(named = "dataset.tune", matches = "true")
    void tuneParameters() throws IOException {
        List<MealSample> samples = new SyntheticMealDataset().generate();
        PredictionParameterTuner.TuneResult result = new PredictionParameterTuner(samples).tune();

        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("tuning-report.md"),
                PredictionParameterTuner.renderMarkdown(result), StandardCharsets.UTF_8);

        assertTrue(result.overallMaeAfter() < result.overallMaeBefore(),
                "寻优未带来改善：before=" + result.overallMaeBefore() + " after=" + result.overallMaeAfter());
    }
}
