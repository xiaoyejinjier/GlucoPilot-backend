package com.threadfit.glucopilot.device.adapter.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.util.StringUtils;

/**
 * 厂商餐段枚举 → GlucoPilot 血糖节点（不含 meal 节点）。
 */
public final class MealSlotMapper {

    private MealSlotMapper() {
    }

    /**
     * 华为 measure_time：1 早餐前 2 早餐后 3 午餐前 4 午餐后 5 晚餐前 6 晚餐后 7 睡前 8 凌晨 9 随机。
     * 睡前/凌晨/随机映射到最近可用血糖节点：7→after_dinner，8→before_breakfast，9→before_breakfast。
     */
    public static String fromHuaweiMeasureTime(int measureTime) {
        return switch (measureTime) {
            case 1 -> GlucoseNodes.BEFORE_BREAKFAST;
            case 2 -> GlucoseNodes.AFTER_BREAKFAST;
            case 3 -> GlucoseNodes.BEFORE_LUNCH;
            case 4 -> GlucoseNodes.AFTER_LUNCH;
            case 5 -> GlucoseNodes.BEFORE_DINNER;
            case 6 -> GlucoseNodes.AFTER_DINNER;
            case 7 -> GlucoseNodes.AFTER_DINNER;
            case 8, 9 -> GlucoseNodes.BEFORE_BREAKFAST;
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 measure_time: " + measureTime);
        };
    }

    /** 华为键值形态：beforeBreakfast / afterLunch 等。 */
    public static String fromHuaweiValueKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "血糖键不能为空");
        }
        return switch (key.trim()) {
            case "beforeBreakfast" -> GlucoseNodes.BEFORE_BREAKFAST;
            case "afterBreakfast" -> GlucoseNodes.AFTER_BREAKFAST;
            case "beforeLunch" -> GlucoseNodes.BEFORE_LUNCH;
            case "afterLunch" -> GlucoseNodes.AFTER_LUNCH;
            case "beforeDinner" -> GlucoseNodes.BEFORE_DINNER;
            case "afterDinner" -> GlucoseNodes.AFTER_DINNER;
            case "beforeBedtime" -> GlucoseNodes.AFTER_DINNER;
            case "earlyMorning" -> GlucoseNodes.BEFORE_BREAKFAST;
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的血糖键: " + key);
        };
    }

    /**
     * Apple HKMetadataKeyBloodGlucoseMealTime：1 餐前 2 餐后（配合本地时分选节点）。
     * Health Connect mealType / relationToMeal 字符串。
     */
    public static String fromMealRelation(String mealHint, java.time.LocalTime time) {
        String hint = mealHint == null ? "" : mealHint.trim().toUpperCase();
        boolean after = hint.contains("AFTER") || hint.contains("POST") || "2".equals(hint);
        boolean before = hint.contains("BEFORE") || hint.contains("PRE") || "1".equals(hint);
        int hour = time.getHour();
        if (hour < 10) {
            return after ? GlucoseNodes.AFTER_BREAKFAST : GlucoseNodes.BEFORE_BREAKFAST;
        }
        if (hour < 15) {
            return after ? GlucoseNodes.AFTER_LUNCH : GlucoseNodes.BEFORE_LUNCH;
        }
        if (before || after) {
            return after ? GlucoseNodes.AFTER_DINNER : GlucoseNodes.BEFORE_DINNER;
        }
        return TimeWindowNodeMapper.resolve(time);
    }
}
