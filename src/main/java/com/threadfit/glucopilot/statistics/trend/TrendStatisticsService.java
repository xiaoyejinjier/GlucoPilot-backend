package com.threadfit.glucopilot.statistics.trend;

import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计页趋势视图：近 N 天按天均值序列与分时段（早/午/晚/其他）统计。
 * 基础按天聚合由 glucose 模块的 GlucoseTrendService 提供，分时段口径由 GlucosePeriodService 提供。
 */
@Service
@RequiredArgsConstructor
public class TrendStatisticsService {

    public static final int DEFAULT_TREND_DAYS = 14;
    public static final int MAX_TREND_DAYS = 90;

    private final GlucoseRecordService glucoseRecordService;
    private final GlucoseTrendService glucoseTrendService;
    private final GlucosePeriodService glucosePeriodService;

    public List<GlucoseDtos.TrendPoint> trend(Long userId, LocalDate endDate, Integer trendDays) {
        int days = trendDays == null ? DEFAULT_TREND_DAYS : Math.min(Math.max(trendDays, 1), MAX_TREND_DAYS);
        LocalDate to = endDate == null ? LocalDate.now() : endDate;
        LocalDate from = to.minusDays(days - 1L);
        return glucoseTrendService.dailySeries(glucoseRecordService.listRange(userId, from, to), from, to);
    }

    public List<GlucoseDtos.SlotStat> slotStats(Long userId, LocalDate from, LocalDate to) {
        return glucosePeriodService.slotStats(glucoseRecordService.listRange(userId, from, to));
    }
}
