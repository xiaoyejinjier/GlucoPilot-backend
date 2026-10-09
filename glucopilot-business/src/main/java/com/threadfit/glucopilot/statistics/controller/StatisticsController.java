package com.threadfit.glucopilot.statistics.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.util.Asserts;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.statistics.daily.DailyStatisticsService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import com.threadfit.glucopilot.statistics.monthly.MonthlyStatisticsService;
import com.threadfit.glucopilot.statistics.trend.TrendStatisticsService;
import com.threadfit.glucopilot.statistics.weekly.WeeklyStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * C 端血糖统计摘要：一次返回日历打卡、月统计、周曲线、分时段、趋势与打卡概览，
 * 避免统计页与「我的」页多次往返。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StatisticsController {

    private final MonthlyStatisticsService monthlyStatisticsService;
    private final WeeklyStatisticsService weeklyStatisticsService;
    private final DailyStatisticsService dailyStatisticsService;
    private final TrendStatisticsService trendStatisticsService;

    @GetMapping("/glucose-records/summary")
    public ApiResponse<StatisticsDtos.Summary> summary(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer trendDays) {
        Long userId = RequestContext.requireUserId();
        YearMonth current = YearMonth.now();
        int resolvedYear = year == null ? current.getYear() : year;
        int resolvedMonth = month == null ? current.getMonthValue() : month;
        Asserts.isTrue(resolvedMonth >= 1 && resolvedMonth <= 12, ErrorCode.BAD_REQUEST, "month 须在 1~12 之间");
        Asserts.isTrue(resolvedYear >= 1970 && resolvedYear <= 9999, ErrorCode.BAD_REQUEST, "year 不合法");
        Asserts.isTrue(startDate == null || endDate == null || !startDate.isAfter(endDate),
                ErrorCode.BAD_REQUEST, "startDate 不能晚于 endDate");
        YearMonth target = YearMonth.of(resolvedYear, resolvedMonth);

        StatisticsDtos.WeekView week = weeklyStatisticsService.week(userId, startDate, endDate);
        List<GlucoseDtos.SlotStat> slotStats = trendStatisticsService.slotStats(
                userId, target.atDay(1), target.atEndOfMonth());

        return ApiResponse.ok(StatisticsDtos.Summary.builder()
                .calendar(monthlyStatisticsService.calendar(userId, resolvedYear, resolvedMonth))
                .monthStats(monthlyStatisticsService.monthStats(userId, resolvedYear, resolvedMonth))
                .week(week)
                .slotStats(slotStats)
                .trend(trendStatisticsService.trend(userId, LocalDate.now(), trendDays))
                .checkIn(dailyStatisticsService.checkIn(userId, LocalDate.now()))
                .build());
    }
}
