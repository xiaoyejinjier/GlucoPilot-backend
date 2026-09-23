package com.threadfit.glucopilot.statistics.daily;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyStatisticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

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
}
