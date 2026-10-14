package com.threadfit.glucopilot.statistics.monthly;

import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 月维度：日历打卡等级与月度统计。
 * 完成数按九时段合并：六个血糖节点 + 三个正餐节点；数值均值/极值仍只来自实测血糖。
 * 等级口径：≥7 次绿(a)、4~6 次橙(b)、1~3 次红(c)，无记录当天不出点。
 */
@Service
@RequiredArgsConstructor
public class MonthlyStatisticsService {

    private static final int LEVEL_A_MIN = 7;
    private static final int LEVEL_B_MIN = 4;
    private static final int TWO_DECIMALS = 2;

    private final GlucoseRecordService glucoseRecordService;
    private final MealRecordService mealRecordService;

    public StatisticsDtos.CalendarView calendar(Long userId, int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        List<GlucoseRecord> records = glucoseRecordService.listRange(
                userId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
        List<MealRecord> meals = mealRecordService.listRange(
                userId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
        Map<Integer, Long> countByDay = records.stream()
                .collect(Collectors.groupingBy(r -> r.getRecordDate().getDayOfMonth(), Collectors.counting()));
        meals.forEach(meal -> countByDay.merge(meal.getRecordDate().getDayOfMonth(), 1L, Long::sum));

        List<StatisticsDtos.CalendarDay> days = countByDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> StatisticsDtos.CalendarDay.builder()
                        .day(entry.getKey())
                        .recordCount(entry.getValue().intValue())
                        .level(levelOf(entry.getValue().intValue()))
                        .build())
                .toList();

        return StatisticsDtos.CalendarView.builder()
                .year(year)
                .month(month)
                .days(days)
                .build();
    }

    public StatisticsDtos.MonthStats monthStats(Long userId, int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        List<GlucoseRecord> records = glucoseRecordService.listRange(
                userId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
        List<MealRecord> meals = mealRecordService.listRange(
                userId, yearMonth.atDay(1), yearMonth.atEndOfMonth());

        Set<LocalDate> recordDates = Stream.concat(
                        records.stream().map(GlucoseRecord::getRecordDate),
                        meals.stream().map(MealRecord::getRecordDate))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        long recordDays = recordDates.size();
        List<BigDecimal> values = records.stream()
                .map(GlucoseRecord::getGlucoseValue)
                .filter(Objects::nonNull)
                .toList();

        return StatisticsDtos.MonthStats.builder()
                .recordDays((int) recordDays)
                .totalRecords(records.size() + meals.size())
                .complianceRate(BigDecimal.valueOf(recordDays)
                        .divide(BigDecimal.valueOf(yearMonth.lengthOfMonth()), TWO_DECIMALS, RoundingMode.HALF_UP))
                .avgValue(GlucoseTrendService.average(records))
                .maxValue(values.stream().max(BigDecimal::compareTo).orElse(null))
                .minValue(values.stream().min(BigDecimal::compareTo).orElse(null))
                .build();
    }

    static String levelOf(int recordCount) {
        if (recordCount >= LEVEL_A_MIN) {
            return "a";
        }
        if (recordCount >= LEVEL_B_MIN) {
            return "b";
        }
        return "c";
    }
}
