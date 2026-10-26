package com.threadfit.glucopilot.statistics.weekly;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 推荐吃法的预测单独聚合，不能进入实测统计。 */
@Service
@RequiredArgsConstructor
public class MealPredictionStatisticsService {

    private final JdbcTemplate jdbc;

    public record DailyPrediction(BigDecimal average, int count) { }

    public Map<LocalDate, DailyPrediction> dailySeries(Long userId, LocalDate from, LocalDate to) {
        Map<LocalDate, DailyPrediction> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT record_date, AVG(predict_suggest) AS predicted_avg, COUNT(*) AS prediction_count
                FROM meal_record
                WHERE user_id = ? AND record_date BETWEEN ? AND ? AND is_deleted = 0
                  AND node_code IN ('breakfast', 'lunch', 'dinner')
                  AND predict_suggest > 0 AND predict_suggest < 40
                GROUP BY record_date ORDER BY record_date
                """, row -> {
                    result.put(row.getDate("record_date").toLocalDate(), new DailyPrediction(
                            row.getBigDecimal("predicted_avg").setScale(1, RoundingMode.HALF_UP),
                            row.getInt("prediction_count")));
                }, userId, from, to);
        return result;
    }
}
