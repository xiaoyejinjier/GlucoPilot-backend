package com.threadfit.glucopilot.prediction.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 预测算法参数（prefix=glucopilot.prediction）。
 * 字段初始值即 v2 默认参数；
 * application.yml 可经 {@code glucopilot.prediction.*} 覆盖，未配置则用本类默认值。
 *
 * <p>v2 依据：ShanghaiT2DM 真实队列（1290 餐 / 42 名患者，留一患者交叉验证）
 * 约束拟合 delta = shrinkK × (target − pre) + R × carbs，目标固定 7.8。
 * 仅 rType2 有实证支撑；rType0/1/3/4 按同一比例（0.484）缩放，**未经临床验证**。
 */
@Data
@Component
@ConfigurationProperties(prefix = "glucopilot.prediction")
public class PredictionProperties {

    /**
     * 回归均值收缩系数 k：dShrink = k × (target − 当前血糖)。
     * 真实数据中餐前血糖每高 1 mmol/L，2h 升幅低 0.44 mmol/L；
     * 全类型共用（回归均值为群体现象，暂假定跨分型一致）。
     */
    private BigDecimal shrinkK = new BigDecimal("0.441");

    /**
     * 响应系数 R（mmol/L per g 碳水），下标 = CodeEnum.DiabetesType 枚举码 0-4。
     * rType2 由 ShanghaiT2DM 队列约束拟合得出（0.0242）；
     * rType0/1/3/4 为按 0.484 比例外推（= 0.0242 / 0.05），**未经临床验证**。
     */
    private BigDecimal rType0 = new BigDecimal("0.0194");
    private BigDecimal rType1 = new BigDecimal("0.0290");
    private BigDecimal rType2 = new BigDecimal("0.0242");
    private BigDecimal rType3 = new BigDecimal("0.0194");
    private BigDecimal rType4 = new BigDecimal("0.0194");

    /**
     * 药物修正（服药后对升幅的修正，mmol/L）。v2 起置 0：
     * ShanghaiT2DM 实证显示服药餐残差反而 +0.64（患者内配对口径）——口服降糖药为慢性背景效应，
     * medicationTaken 入参保留采集，待真实用户「服药餐 + 餐后实测」数据回流后再实证校准。
     */
    private BigDecimal medEffect = BigDecimal.ZERO;

    /** 运动修正（运动后对升幅的削减，mmol/L）；数据集无运动字段，无法验证，沿用 v1。 */
    private BigDecimal exerciseEffect = new BigDecimal("-0.3");

    /** 餐后默认目标（targetPostprandial/targetFasting 均为 null 时兜底，mmol/L）。 */
    private BigDecimal defaultTarget = new BigDecimal("7.8");

    /** 单餐建议碳水上限（g）；待确认。 */
    private BigDecimal carbsSuggestMax = new BigDecimal("200");

    /** 熟米饭碳水比（g 碳水 / g 米饭）；参考值待确认。 */
    private BigDecimal riceCarbRatio = new BigDecimal("0.26");

    /** 主食类（categoryCode=1）建议克重下限（g）；默认值，最终需营养师确认。 */
    private BigDecimal stapleFloor = new BigDecimal("50");

    /** 高血糖警示阈值（mmol/L）；P3 起为 13.9，与 sys_config.glucose.danger.high 互不联动。 */
    private BigDecimal dangerHigh = new BigDecimal("13.9");

    /** 高血糖提示阈值（mmol/L）；cur≥该值且非 DANGER 时叠加 CAUTION 提示位。 */
    private BigDecimal cautionHigh = new BigDecimal("10");

    /** 离散搜索最低份量档（ABOVE 向下搜索的下限，50%）。 */
    private BigDecimal minPortionRatio = new BigDecimal("0.5");

    /** 安全裕度（mmol/L）：一般/年轻/老年档；GDM 用 safetyMarginGdm。 */
    private BigDecimal safetyMargin = new BigDecimal("1.0");

    /** 妊娠期（GDM）安全裕度（mmol/L）；6.7 本身已严格，裕度过大会导致大面积 INFEASIBLE。 */
    private BigDecimal safetyMarginGdm = new BigDecimal("0.3");

    /** 整餐碳水下限（g）；GDM/老年不允许 0 碳水时作为 floor。 */
    private BigDecimal minMealCarbs = new BigDecimal("30");
}