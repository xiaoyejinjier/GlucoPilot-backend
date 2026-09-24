package com.threadfit.glucopilot.statistics.monthly;

import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyStatisticsServiceTest {

    @Mock
    private GlucoseRecordService glucoseRecordService;

    @InjectMocks
    private MonthlyStatisticsService service;

    @Test
    void calendarLevelsFollowRecordCount() {
        when(glucoseRecordService.listRange(eq(1L), any(), any())).thenReturn(septemberRecords());

        StatisticsDtos.CalendarView calendar = service.calendar(1L, 2026, 9);

        Map<Integer, String> levelByDay = calendar.getDays().stream()
                .collect(Collectors.toMap(StatisticsDtos.CalendarDay::getDay, StatisticsDtos.CalendarDay::getLevel));
        assertEquals("c", levelByDay.get(1));
        assertEquals("c", levelByDay.get(2));
        assertEquals("b", levelByDay.get(3));
        assertEquals("b", levelByDay.get(4));
        assertEquals("a", levelByDay.get(5));
        // 无记录的日期不出点
        assertNull(levelByDay.get(6));
        assertEquals(5, calendar.getDays().size());
    }

    @Test
    void monthStatsComputeComplianceAndExtremes() {
        when(glucoseRecordService.listRange(eq(1L), any(), any())).thenReturn(septemberRecords());

        StatisticsDtos.MonthStats stats = service.monthStats(1L, 2026, 9);

        assertEquals(5, stats.getRecordDays());
        assertEquals(21, stats.getTotalRecords());
        // 5 / 30 天，保留两位四舍五入
        assertEquals(new BigDecimal("0.17"), stats.getComplianceRate());
        assertEquals(new BigDecimal("6.1"), stats.getAvgValue());
        assertEquals(new BigDecimal("8.2"), stats.getMaxValue());
        assertEquals(new BigDecimal("5.6"), stats.getMinValue());
    }

    /** 9 月 1~5 日分别有 1/3/4/6/7 条记录，共 21 条；极值来自 8.2 与 5.6。 */
    private static List<GlucoseRecord> septemberRecords() {
        int[] counts = {1, 3, 4, 6, 7};
        List<GlucoseRecord> records = new ArrayList<>();
        for (int day = 1; day <= counts.length; day++) {
            for (int i = 0; i < counts[day - 1]; i++) {
                records.add(record(day, "6.0"));
            }
        }
        records.get(0).setGlucoseValue(new BigDecimal("8.2"));
        records.get(1).setGlucoseValue(new BigDecimal("5.6"));
        return records;
    }

    private static GlucoseRecord record(int day, String value) {
        GlucoseRecord row = new GlucoseRecord();
        row.setUserId(1L);
        row.setRecordDate(LocalDate.of(2026, 9, day));
        row.setNodeCode("before_breakfast");
        row.setGlucoseValue(new BigDecimal(value));
        return row;
    }
}
