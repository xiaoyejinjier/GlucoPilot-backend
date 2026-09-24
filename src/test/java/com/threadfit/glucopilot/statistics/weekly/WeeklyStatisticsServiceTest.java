package com.threadfit.glucopilot.statistics.weekly;

import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyStatisticsServiceTest {

    @Mock
    private GlucoseRecordService glucoseRecordService;

    private WeeklyStatisticsService service;

    @BeforeEach
    void setUp() {
        // 按天聚合是真实计算，用真的 GlucoseTrendService 才能覆盖补点逻辑
        service = new WeeklyStatisticsService(glucoseRecordService, new GlucoseTrendService());
    }

    @Test
    void weekFillsMissingDaysAndComputesExtremes() {
        when(glucoseRecordService.listRange(eq(1L), any(), any())).thenReturn(List.of(
                record(LocalDate.of(2026, 9, 21), "6.0"),
                record(LocalDate.of(2026, 9, 21), "8.0"),
                record(LocalDate.of(2026, 9, 23), "6.0")));

        StatisticsDtos.WeekView week = service.week(1L,
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27));

        assertEquals(7, week.getPoints().size());
        assertEquals(List.of("一", "二", "三", "四", "五", "六", "日"), week.getLabels());
        assertEquals(new BigDecimal("7.0"), week.getPoints().get(0).getAvg());
        assertEquals(2, week.getPoints().get(0).getCount());
        assertEquals(new BigDecimal("6.0"), week.getPoints().get(2).getAvg());
        // 无记录的天保留占位点，均值为空
        assertNull(week.getPoints().get(1).getAvg());
        assertEquals(0, week.getPoints().get(1).getCount());
        assertEquals(new BigDecimal("6.5"), week.getAvg());
        assertEquals(new BigDecimal("7.0"), week.getMax());
        assertEquals(new BigDecimal("6.0"), week.getMin());
    }

    @Test
    void weekWithoutRecordsYieldsNullAverages() {
        when(glucoseRecordService.listRange(eq(1L), any(), any())).thenReturn(List.of());

        StatisticsDtos.WeekView week = service.week(1L,
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27));

        assertEquals(7, week.getPoints().size());
        assertNull(week.getAvg());
        assertNull(week.getMax());
        assertNull(week.getMin());
    }

    private static GlucoseRecord record(LocalDate date, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setUserId(1L);
        row.setRecordDate(date);
        row.setNodeCode("before_breakfast");
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }
}
