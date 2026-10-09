package com.threadfit.glucopilot.statistics.weekly;

import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;

/**
 * 周维度：统计页周曲线（按天均值）与周均/最高/最低。
 * 缺省取包含今天的自然周（周一~周日）；传入区间时原样使用。
 */
@Service
@RequiredArgsConstructor
public class WeeklyStatisticsService {

    private static final String WEEK_LABELS = "一二三四五六日";
    private static final int ONE_DECIMAL = 1;

    private final GlucoseRecordService glucoseRecordService;
    private final GlucoseTrendService glucoseTrendService;

    public StatisticsDtos.WeekView week(Long userId, LocalDate startDate, LocalDate endDate) {
        LocalDate from;
        LocalDate to;
        if (startDate == null && endDate == null) {
            from = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            to = from.plusDays(6);
        } else {
            from = startDate != null ? startDate : endDate.minusDays(6);
            to = endDate != null ? endDate : from.plusDays(6);
        }

        List<GlucoseDtos.TrendPoint> series = glucoseTrendService.dailySeries(
                glucoseRecordService.listRange(userId, from, to), from, to);

        List<StatisticsDtos.WeekPoint> points = series.stream()
                .map(p -> StatisticsDtos.WeekPoint.builder()
                        .date(p.getDate())
                        .label(labelOf(p.getDate()))
                        .avg(p.getAvg())
                        .count(p.getCount())
                        .build())
                .toList();

        List<BigDecimal> averages = series.stream()
                .map(GlucoseDtos.TrendPoint::getAvg)
                .filter(Objects::nonNull)
                .toList();

        return StatisticsDtos.WeekView.builder()
                .startDate(from)
                .endDate(to)
                .labels(points.stream().map(StatisticsDtos.WeekPoint::getLabel).toList())
                .points(points)
                .avg(averages.isEmpty() ? null
                        : averages.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(averages.size()), ONE_DECIMAL, RoundingMode.HALF_UP))
                .max(averages.stream().max(BigDecimal::compareTo).orElse(null))
                .min(averages.stream().min(BigDecimal::compareTo).orElse(null))
                .build();
    }

    static String labelOf(LocalDate date) {
        return String.valueOf(WEEK_LABELS.charAt(date.getDayOfWeek().getValue() - 1));
    }
}
