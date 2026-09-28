package com.threadfit.glucopilot.statistics.dto;

import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * C 端血糖统计。calendar / monthStats 与接口设计文档 2.18 一致，
 * week / slotStats / trend / checkIn 为端上统计页与「我的」页补充字段。
 */
public final class StatisticsDtos {

    private StatisticsDtos() {
    }

    @Data
    @Builder
    public static class CalendarDay {
        private Integer day;
        private Integer recordCount;
        /** a 绿（≥7 次）/ b 橙（4~6 次）/ c 红（1~3 次） */
        private String level;
    }

    @Data
    @Builder
    public static class CalendarView {
        private Integer year;
        private Integer month;
        private List<CalendarDay> days;
    }

    @Data
    @Builder
    public static class MonthStats {
        private Integer recordDays;
        private Integer totalRecords;
        /** 有记录天数 / 当月总天数，两位小数 */
        private BigDecimal complianceRate;
        private BigDecimal avgValue;
        private BigDecimal maxValue;
        private BigDecimal minValue;
    }

    @Data
    @Builder
    public static class WeekPoint {
        private LocalDate date;
        private String label;
        private BigDecimal avg;
        private Integer count;
    }

    @Data
    @Builder
    public static class WeekView {
        private LocalDate startDate;
        private LocalDate endDate;
        private List<String> labels;
        private List<WeekPoint> points;
        private BigDecimal avg;
        private BigDecimal max;
        private BigDecimal min;
    }

    /** 打卡概览，供「我的」页与统计页共同使用。 */
    @Data
    @Builder
    public static class CheckIn {
        private Integer todayCount;
        /** 今日血糖均值；今天没有记录时为 null */
        private BigDecimal todayAvg;
        private Integer totalNodes;
        /** 以今天为终点连续有记录的天数；今天没记录则为 0 */
        private Integer streakDays;
        private Integer monthRecordDays;
    }

    @Data
    @Builder
    public static class Summary {
        private CalendarView calendar;
        private MonthStats monthStats;
        private WeekView week;
        private List<GlucoseDtos.SlotStat> slotStats;
        private List<GlucoseDtos.TrendPoint> trend;
        private CheckIn checkIn;
    }
}
