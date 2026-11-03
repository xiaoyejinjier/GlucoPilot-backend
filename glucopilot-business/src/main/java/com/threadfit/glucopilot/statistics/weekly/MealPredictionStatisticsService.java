package com.threadfit.glucopilot.statistics.weekly;

import com.threadfit.glucopilot.statistics.mapper.MealPredictionStatisticsMapper;

import lombok.RequiredArgsConstructor;

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

    private final MealPredictionStatisticsMapper mapper;

    public record DailyPrediction(BigDecimal average, int count) {}

    public Map<LocalDate, DailyPrediction> dailySeries(Long userId, LocalDate from, LocalDate to) {
        Map<LocalDate, DailyPrediction> result = new LinkedHashMap<>();
        for (var row : mapper.selectDailySeries(userId, from, to)) {
            result.put(
                    row.getRecordDate(),
                    new DailyPrediction(
                            row.getPredictedAvg().setScale(1, RoundingMode.HALF_UP),
                            row.getPredictionCount()));
        }
        return result;
    }
}
