package com.threadfit.glucopilot.community.shared.support;

import java.time.LocalDateTime;
import java.util.Map;

/** Null-safe conversion of joined/aggregate MyBatis projections; never executes SQL. */
public final class ProjectionValues {
    private ProjectionValues() {}

    public static Object value(Map<String, Object> row, String key) {
        return row.get(key);
    }

    public static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    public static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    public static int intValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).intValue();
    }

    public static LocalDateTime time(Map<String, Object> row, String key) {
        return dateTime(row.get(key));
    }

    public static LocalDateTime dateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        throw new IllegalStateException(
                "Unsupported database timestamp: " + value.getClass().getName());
    }
}
