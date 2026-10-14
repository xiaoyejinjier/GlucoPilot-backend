package com.threadfit.glucopilot.statistics.daily;

import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 打卡概览：今日打卡次数、连续打卡天数与当月打卡天数。 */
@Service
@RequiredArgsConstructor
public class DailyStatisticsService {

    /** 连续打卡回溯上限，避免一次拉取过多历史日期。 */
    private static final int STREAK_LOOKBACK_DAYS = 365;

    private final GlucoseRecordService glucoseRecordService;
    private final GlucosePeriodService glucosePeriodService;
    private final MealRecordService mealRecordService;

    public StatisticsDtos.CheckIn checkIn(Long userId, LocalDate today) {
        List<GlucoseRecord> todayRecords = glucoseRecordService.listRange(userId, today, today);
        List<MealRecord> todayMeals = mealRecordService.listRange(userId, today, today);
        LocalDate lookbackStart = today.minusDays(STREAK_LOOKBACK_DAYS);
        Set<LocalDate> recordedDates = new HashSet<>(glucoseRecordService.recordedDatesDesc(
                userId, lookbackStart, today));
        mealRecordService.listRange(userId, lookbackStart, today).stream()
                .map(MealRecord::getRecordDate)
                .forEach(recordedDates::add);
        YearMonth yearMonth = YearMonth.of(today.getYear(), today.getMonthValue());
        Set<LocalDate> monthDates = new HashSet<>(glucoseRecordService.recordedDatesDesc(
                userId, yearMonth.atDay(1), yearMonth.atEndOfMonth()));
        mealRecordService.listRange(userId, yearMonth.atDay(1), yearMonth.atEndOfMonth()).stream()
                .map(MealRecord::getRecordDate)
                .forEach(monthDates::add);

        return StatisticsDtos.CheckIn.builder()
                .todayCount(todayRecords.size() + todayMeals.size())
                .todayAvg(GlucoseTrendService.average(todayRecords))
                .totalNodes(glucosePeriodService.listEnabledNodes().size())
                .streakDays(streakOf(recordedDates, today))
                .monthRecordDays(monthDates.size())
                .build();
    }

    /** 以今天为终点向前数：今天无记录即为 0，不把「昨天起连续」算成当前连续。 */
    static int streakOf(Set<LocalDate> recordedDates, LocalDate today) {
        int streak = 0;
        LocalDate cursor = today;
        while (recordedDates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
