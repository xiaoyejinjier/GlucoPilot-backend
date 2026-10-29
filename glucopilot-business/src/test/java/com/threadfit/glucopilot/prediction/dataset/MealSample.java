package com.threadfit.glucopilot.prediction.dataset;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合成餐后血糖数据集的单条样本：预测入参特征 + 教师模型标签 + 诊断信息。
 * 仅测试作用域，不进入生产代码；字段与线上 {@code PredictionDtos.PredictRequest} 对齐。
 */
public record MealSample(
        int sampleId,
        /** 虚拟用户 ID（1-200），同 ID 恒定绑定同一糖尿病类型与个体敏感系数。 */
        long virtualUserId,
        /** 糖尿病类型 0-4（0=未建档/其他）。 */
        int diabetesType,
        /** 餐前血糖（mmol/L，1 位小数）。 */
        BigDecimal currentGlucose,
        /** 碳水汇总（g，1 位小数），恒等于 items 碳水之和（自洽约束）。 */
        BigDecimal carbsG,
        /** 份量比例（2 位小数）。 */
        BigDecimal portionRatio,
        boolean medicationTaken,
        boolean exerciseDone,
        List<SampleItem> items,
        /** 教师模型标签：模拟餐后 2h 实测血糖（mmol/L，模拟血糖仪 2 位精度）。 */
        BigDecimal postprandialMeasured,
        /** 诊断：碳水升幅（未含药物/运动修正与噪声，mmol/L）。 */
        BigDecimal teacherDelta,
        /** 诊断：碳水占比加权的 GI 因子。 */
        BigDecimal giFactor,
        /** 诊断：虚拟用户个体敏感系数（N(1,0.25) 截断 [0.5,1.75]）。 */
        BigDecimal sens
) {

    /** 样本食物明细：与线上 Item 字段对齐，但独立定义以免耦合生产 DTO。 */
    public record SampleItem(
            String foodName,
            int categoryCode,
            /** 展示用克重（g），由碳水密度反推，不参与公式计算。 */
            BigDecimal weightG,
            BigDecimal carbsG
    ) {
    }
}
