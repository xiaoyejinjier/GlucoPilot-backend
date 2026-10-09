package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.statistics.daily.DailyStatisticsService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import com.threadfit.glucopilot.statistics.monthly.MonthlyStatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 打卡与月度统计工具。
 * <p>
 * 这两个工具的共同点：<b>它们回答的是"坚持得怎么样"而不是"某次血糖多少"</b>——
 * 属于行为统计，与 {@link GlucoseQueryTools} 的明细查询互不重叠，因此模型容易选对。
 * <p>
 * <b>默认时间一律由服务端决定</b>：模型不知道今天是几号（实测过），
 * 所以 {@code year}/{@code month} 省略时必须落到"当前月"，而不是报参数错误。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsTools {

    private final DailyStatisticsService dailyStatisticsService;
    private final MonthlyStatisticsService monthlyStatisticsService;
    private final JsonUtils jsonUtils;

    @Tool(name = "get_daily_checkin",
            description = "查询当前用户今天的记录完成情况：今天已记录几条、平均值、应记录总时段数、已连续记录天数、本月已记录天数。"
                    + "用户问「我今天记了吗」「还差几条」「我连续记了几天」「我坚持得怎么样」时使用。")
    public String getDailyCheckin(ToolContext toolContext) {
        Long userId = AiToolContext.requireUserId(toolContext);

        StatisticsDtos.CheckIn checkIn;
        try {
            checkIn = dailyStatisticsService.checkIn(userId, LocalDate.now());
        } catch (RuntimeException e) {
            log.error("工具 get_daily_checkin 查询失败 userId={}", userId, e);
            return error("查询今日记录情况时出错，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todayCount", checkIn.getTodayCount());
        result.put("todayAvg", checkIn.getTodayAvg());
        result.put("totalNodes", checkIn.getTotalNodes());
        result.put("streakDays", checkIn.getStreakDays());
        result.put("monthRecordDays", checkIn.getMonthRecordDays());
        log.info("工具 get_daily_checkin 执行 userId={} todayCount={} streak={}",
                userId, checkIn.getTodayCount(), checkIn.getStreakDays());
        return jsonUtils.toJson(result);
    }

    @Tool(name = "get_month_stats",
            description = "查询某个月的血糖统计：记录天数、总记录数、达标率、平均/最高/最低血糖值。"
                    + "用户问「这个月达标率多少」「这个月情况」时使用。year 与 month 都省略即表示当前月，不需要向用户确认今天是几号。")
    public String getMonthStats(
            @ToolParam(required = false, description = "年份，如 2026；省略表示今年")
            Integer year,
            @ToolParam(required = false, description = "月份 1-12；省略表示本月")
            Integer month,
            ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);
        LocalDate today = LocalDate.now();
        int targetYear = year == null ? today.getYear() : year;
        int targetMonth = month == null ? today.getMonthValue() : month;

        if (targetMonth < 1 || targetMonth > 12) {
            return error("month 必须是 1-12 之间的整数");
        }
        if (targetYear < 2000 || targetYear > 2100) {
            return error("year 不合理，请给出四位年份，例如 2026");
        }

        StatisticsDtos.MonthStats stats;
        try {
            stats = monthlyStatisticsService.monthStats(userId, targetYear, targetMonth);
        } catch (RuntimeException e) {
            log.error("工具 get_month_stats 查询失败 userId={} {}-{}", userId, targetYear, targetMonth, e);
            return error("查询月度统计时出错，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", targetYear);
        result.put("month", targetMonth);
        result.put("recordDays", stats.getRecordDays());
        result.put("totalRecords", stats.getTotalRecords());
        result.put("complianceRate", stats.getComplianceRate());
        result.put("avgValue", stats.getAvgValue());
        result.put("maxValue", stats.getMaxValue());
        result.put("minValue", stats.getMinValue());
        log.info("工具 get_month_stats 执行 userId={} {}-{} 记录天数={}",
                userId, targetYear, targetMonth, stats.getRecordDays());
        return jsonUtils.toJson(result);
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
