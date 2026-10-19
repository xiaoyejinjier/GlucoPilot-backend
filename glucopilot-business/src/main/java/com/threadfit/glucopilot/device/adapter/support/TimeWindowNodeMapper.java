package com.threadfit.glucopilot.device.adapter.support;

import com.threadfit.glucopilot.common.constant.GlucoseNodes;

import java.time.LocalTime;

/**
 * 按本地时分落入血糖节点窗口（CGM 流式样本用）。
 */
public final class TimeWindowNodeMapper {

    private TimeWindowNodeMapper() {
    }

    public static String resolve(LocalTime time) {
        int minutes = time.getHour() * 60 + time.getMinute();
        // 05:00-08:29 before_breakfast; 08:30-10:29 after_breakfast;
        // 10:30-12:29 before_lunch; 12:30-15:29 after_lunch;
        // 15:30-17:59 before_dinner; else after_dinner
        if (minutes < 5 * 60) {
            return GlucoseNodes.BEFORE_BREAKFAST;
        }
        if (minutes < 8 * 60 + 30) {
            return GlucoseNodes.BEFORE_BREAKFAST;
        }
        if (minutes < 10 * 60 + 30) {
            return GlucoseNodes.AFTER_BREAKFAST;
        }
        if (minutes < 12 * 60 + 30) {
            return GlucoseNodes.BEFORE_LUNCH;
        }
        if (minutes < 15 * 60 + 30) {
            return GlucoseNodes.AFTER_LUNCH;
        }
        if (minutes < 18 * 60) {
            return GlucoseNodes.BEFORE_DINNER;
        }
        return GlucoseNodes.AFTER_DINNER;
    }
}
