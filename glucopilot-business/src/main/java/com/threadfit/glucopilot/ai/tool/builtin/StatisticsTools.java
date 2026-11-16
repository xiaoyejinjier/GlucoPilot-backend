package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.ai.tool.support.GlucoseRates;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.service.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.service.GlucoseRecordService;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import com.threadfit.glucopilot.statistics.daily.service.DailyStatisticsService;
import com.threadfit.glucopilot.statistics.dto.StatisticsDtos;
import com.threadfit.glucopilot.statistics.monthly.service.MonthlyStatisticsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 打卡与月度统计工具。
 *
 * <p>这两个工具的共同点：<b>它们回答的是"坚持得怎么样"而不是"某次血糖多少"</b>—— 属于行为统计，与 {@link GlucoseQueryTools}
 * 的明细查询互不重叠，因此模型容易选对。
 *
 * <p><b>默认时间一律由服务端决定</b>：模型不知道今天是几号（实测过）， 所以 {@code year}/{@code month} 省略时必须落到"当前月"，而不是报参数错误。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsTools {

    private final DailyStatisticsService dailyStatisticsService;
    private final MonthlyStatisticsService monthlyStatisticsService;
    private final GlucoseRecordService glucoseRecordService;
    private final GlucosePeriodService glucosePeriodService;
    private final HealthProfileService healthProfileService;
    private final JsonUtils jsonUtils;

    @Tool(
            name = "get_daily_checkin",
            description =
                    "查询当前用户今天的记录完成情况：今天已记录几条、平均值、应记录总时段数、已连续记录天数、本月已记录天数。"
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
        log.info(
                "工具 get_daily_checkin 执行 userId={} todayCount={} streak={}",
                userId,
                checkIn.getTodayCount(),
                checkIn.getStreakDays());
        return jsonUtils.toJson(result);
    }

    @Tool(
            name = "get_month_stats",
            description =
                    "查询某个月的血糖统计：记录天数、总记录数、打卡率（当月有记录的天数占比）、"
                            + "血糖达标率（记录落在目标区间内的比例）、平均/最高/最低血糖值。"
                            + "用户问「这个月达标率多少」「这个月坚持得怎么样」时使用。"
                            + "year 与 month 都省略即表示当前月，不需要向用户确认今天是几号。")
    public String getMonthStats(
            @ToolParam(required = false, description = "年份，如 2026；省略表示今年") Integer year,
            @ToolParam(required = false, description = "月份 1-12；省略表示本月") Integer month,
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
            log.error(
                    "工具 get_month_stats 查询失败 userId={} {}-{}", userId, targetYear, targetMonth, e);
            return error("查询月度统计时出错，请稍后重试");
        }

        // 达标率要另算：MonthlyStatisticsService 的 complianceRate 其实是「打卡覆盖率」，
        // 直接叫「达标率」会让模型对用户说错话（口径见 GlucoseRates 的类注释）。
        GlucoseRates.Targets targets = resolveTargets(userId);
        GlucoseRates.Tally tally = monthTally(userId, targetYear, targetMonth, targets);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", targetYear);
        result.put("month", targetMonth);
        result.put("recordDays", stats.getRecordDays());
        result.put("totalRecords", stats.getTotalRecords());
        result.put("checkinRate", stats.getComplianceRate());
        result.put(
                "checkinRateNote",
                "checkinRate = 当月有记录的天数 / 当月总天数，衡量打卡坚持度，不是血糖达标率");
        Map<String, Object> inRange = new LinkedHashMap<>();
        inRange.put("count", tally.inRange());
        inRange.put("total", tally.total());
        inRange.put("rate", tally.inRangeRate());
        inRange.put("highRate", tally.highRate());
        inRange.put("lowRate", tally.lowRate());
        result.put("inRange", inRange);
        Map<String, Object> targetMap = new LinkedHashMap<>();
        targetMap.put("fasting", targets.fasting());
        targetMap.put("postprandial", targets.postprandial());
        targetMap.put("low", GlucoseRates.LOW_LIMIT);
        targetMap.put("source", targets.fromProfile() ? "profile" : "default");
        result.put("targets", targetMap);
        result.put("avgValue", stats.getAvgValue());
        result.put("maxValue", stats.getMaxValue());
        result.put("minValue", stats.getMinValue());
        result.put(
                "hint",
                tally.total() == 0
                        ? "该月没有任何血糖记录，只有打卡数据；不要用 0 充当达标率。"
                        : "回答「达标率」请用 inRange.rate，不要把 checkinRate 说成达标率。"
                                + (targets.fromProfile() ? "" : "本次按通用目标值（档案未填），回答里要说明这一点。"));
        log.info(
                "工具 get_month_stats 执行 userId={} {}-{} 记录天数={}",
                userId,
                targetYear,
                targetMonth,
                stats.getRecordDays());
        return jsonUtils.toJson(result);
    }

    /** 目标值读取失败不该让整轮失败：回落到通用值，并在返回里标注来源。 */
    private GlucoseRates.Targets resolveTargets(Long userId) {
        try {
            return GlucoseRates.targets(healthProfileService.healthViewOf(userId));
        } catch (RuntimeException e) {
            log.warn("读取健康档案目标值失败，本次按通用目标统计 userId={}", userId, e);
            return GlucoseRates.targets(null);
        }
    }

    /** 当月血糖的达标统计；查询失败返回零计数（调用方据此给 hint，而不是编一个数字）。 */
    private GlucoseRates.Tally monthTally(
            Long userId, int year, int month, GlucoseRates.Targets targets) {
        YearMonth yearMonth = YearMonth.of(year, month);
        try {
            List<GlucoseRecord> rows =
                    glucoseRecordService.listRange(
                            userId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
            Map<String, GlucoseNode> nodes = glucosePeriodService.nodeMap();
            List<BigDecimal> values = new ArrayList<>(rows.size());
            List<Boolean> meals = new ArrayList<>(rows.size());
            for (GlucoseRecord row : rows) {
                values.add(row.getGlucoseValue());
                meals.add(GlucosePeriodService.isMeal(nodes.get(row.getNodeCode())));
            }
            return GlucoseRates.tally(values, meals, targets);
        } catch (RuntimeException e) {
            log.warn("计算当月达标率失败 userId={} {}-{}", userId, year, month, e);
            return new GlucoseRates.Tally(0, 0, 0, 0);
        }
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
