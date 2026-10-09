package com.threadfit.glucopilot.prediction.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 预测算法参数（prefix=glucopilot.prediction）。
 * 字段初始值即 v1 默认参数，多数系数待临床确认、上线前须校准；
 * application.yml 可经 {@code glucopilot.prediction.*} 覆盖，未配置则用本类默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "glucopilot.prediction")
public class PredictionProperties {

    /** 响应系数 R（mmol/L per g 碳水），下标 = CodeEnum.DiabetesType 枚举码 0-4；分档幅度待临床确认。 */
    private BigDecimal rType0 = new BigDecimal("0.04");
    private BigDecimal rType1 = new BigDecimal("0.06");
    private BigDecimal rType2 = new BigDecimal("0.05");
    private BigDecimal rType3 = new BigDecimal("0.04");
    private BigDecimal rType4 = new BigDecimal("0.04");

    /** 药物修正（服药后对升幅的削减，mmol/L）；待临床确认。 */
    private BigDecimal medEffect = new BigDecimal("-0.5");

    /** 运动修正（运动后对升幅的削减，mmol/L）；待临床确认。 */
    private BigDecimal exerciseEffect = new BigDecimal("-0.3");

    /** 餐后默认目标（targetPostprandial/targetFasting 均为 null 时兜底，mmol/L）。 */
    private BigDecimal defaultTarget = new BigDecimal("7.8");

    /** 单餐建议碳水上限（g）；待确认。 */
    private BigDecimal carbsSuggestMax = new BigDecimal("200");

    /** 熟米饭碳水比（g 碳水 / g 米饭）；参考值待确认。 */
    private BigDecimal riceCarbRatio = new BigDecimal("0.26");

    /** 主食类（categoryCode=1）建议克重下限（g）；默认值，最终需营养师确认。 */
    private BigDecimal stapleFloor = new BigDecimal("30");

    /** 高血糖警示阈值（mmol/L）；与 sys_config.glucose.danger.high 默认一致。 */
    private BigDecimal dangerHigh = new BigDecimal("10");
}