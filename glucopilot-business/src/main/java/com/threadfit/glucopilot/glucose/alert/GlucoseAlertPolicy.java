package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import java.math.BigDecimal;

/** 产品提醒阈值，与旧图表达标颜色独立。 */
public final class GlucoseAlertPolicy {
    public static final BigDecimal LOW = new BigDecimal("3.9");
    public static final BigDecimal HIGH = new BigDecimal("10.0");
    private GlucoseAlertPolicy() { }

    public static String classify(String nodeCode, BigDecimal value) {
        if (nodeCode == null || !GlucoseNodes.isGlucoseNode(nodeCode) || value == null
                || value.signum() <= 0 || value.compareTo(new BigDecimal("40")) >= 0) return null;
        if (value.compareTo(LOW) < 0) return "LOW";
        return value.compareTo(HIGH) >= 0 ? "HIGH" : null;
    }

    public static String nodeName(String code) {
        return switch (code) {
            case "before_breakfast" -> "早餐前";
            case "after_breakfast" -> "早餐后";
            case "before_lunch" -> "午餐前";
            case "after_lunch" -> "午餐后";
            case "before_dinner" -> "晚餐前";
            case "after_dinner" -> "晚餐后";
            default -> code;
        };
    }
}
