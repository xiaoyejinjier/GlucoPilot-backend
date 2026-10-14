package com.threadfit.glucopilot.statistics.daily;

import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.meal.entity.MealRecord;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyStatisticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    @Mock
    private GlucoseRecordService glucoseRecordService;
    @Mock
    private GlucosePeriodService glucosePeriodService;
    @Mock
    private MealRecordService mealRecordService;
    @InjectMocks
    private DailyStatisticsService service;

    @Test
    void checkInCombinesSixGlucoseAndThreeMealSlots() {
        LocalDate lookback = TODAY.minusDays(365);
        YearMonth month = YearMonth.from(TODAY);
        when(glucoseRecordService.listRange(1L, TODAY, TODAY)).thenReturn(List.of(
                glucose(TODAY, "5.8"), glucose(TODAY, "7.2")));
        when(mealRecordService.listRange(1L, TODAY, TODAY)).thenReturn(List.of(meal(TODAY)));
        when(glucoseRecordService.recordedDatesDesc(1L, lookback, TODAY))
                .thenReturn(List.of(TODAY, TODAY.minusDays(1)));
        when(mealRecordService.listRange(1L, lookback, TODAY))
                .thenReturn(List.of(meal(TODAY.minusDays(2))));
        when(glucoseRecordService.recordedDatesDesc(1L, month.atDay(1), month.atEndOfMonth()))
                .thenReturn(List.of(TODAY, TODAY.minusDays(1)));
        when(mealRecordService.listRange(1L, month.atDay(1), month.atEndOfMonth()))
                .thenReturn(List.of(meal(TODAY.minusDays(2))));
        when(glucosePeriodService.listEnabledNodes())
                .thenReturn(Collections.nCopies(9, new GlucoseNode()));

        StatisticsDtos.CheckIn result = service.checkIn(1L, TODAY);

        assertEquals(3, result.getTodayCount());
        assertEquals(new BigDecimal("6.5"), result.getTodayAvg());
        assertEquals(9, result.getTotalNodes());
        assertEquals(3, result.getStreakDays());
        assertEquals(3, result.getMonthRecordDays());
    }

    @Test
    void countsConsecutiveDaysEndingToday() {
        Set<LocalDate> dates = Set.of(
                TODAY,
                TODAY.minusDays(1),
                TODAY.minusDays(2),
                // 断档一天后再往前
                TODAY.minusDays(4));

        assertEquals(3, DailyStatisticsService.streakOf(dates, TODAY));
    }

    @Test
    void streakIsZeroWhenTodayHasNoRecord() {
        Set<LocalDate> dates = Set.of(TODAY.minusDays(1), TODAY.minusDays(2));

        assertEquals(0, DailyStatisticsService.streakOf(dates, TODAY));
    }

    @Test
    void streakIsZeroWithoutAnyRecord() {
        assertEquals(0, DailyStatisticsService.streakOf(Set.of(), TODAY));
    }

    private static GlucoseRecord glucose(LocalDate date, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setRecordDate(date);
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }

    private static MealRecord meal(LocalDate date) {
        MealRecord row = new MealRecord();
        row.setRecordDate(date);
        row.setNodeCode("breakfast");
        return row;
    }
}
