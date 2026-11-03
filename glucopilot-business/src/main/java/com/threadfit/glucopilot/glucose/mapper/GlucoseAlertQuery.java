package com.threadfit.glucopilot.glucose.mapper;

import java.time.LocalDate;

/** 异常血糖筛选值，关键词仍按字面子串匹配，不作为SQL片段。 */
public record GlucoseAlertQuery(
        LocalDate start, LocalDate end, String keyword, String alertType, String status) {
    public GlucoseAlertQuery {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
