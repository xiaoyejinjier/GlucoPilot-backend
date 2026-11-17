package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.ai.tool.support.GlucoseRates;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.service.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.service.GlucoseRecordService;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 血糖「只看结论」的统计工具：给定一段区间，返回均值/极值/波动/达标率/分时段表现。
 *
 * <p><b>与 {@link GlucoseQueryTools} 的分工</b>（两个工具刻意不重叠，模型才好选）：
 *
 * <ul>
 *   <li>{@code query_glucose_range}：带明细，限制 ≤31 天 / ≤60 条——回答「我昨天吃了什么、血糖多少」；
 *   <li>{@code query_glucose_stats}：不给明细、只给聚合，最长 92 天——回答「这两个月控制得怎么样」。
 * </ul>
 *
 * <p>之所以不把长区间并进明细工具：60 条明细已经把上下文吃掉一大半，而「三个月怎么样」
 * 这类问题的答案本来就是几个数字。这也是本工具不返回 {@code items} 的原因。
 *
 * <p><b>达标口径只有一处实现</b>（{@link GlucoseRates}）：偏低 &lt; 3.9、达标 3.9~目标值、
 * 偏高 &gt; 目标值；餐点时段用餐后目标、其余用空腹目标，目标值取用户健康档案，缺失时兜底并在
 * 返回里标注 {@code targets.source=default}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlucoseStatsTools {

    /** 单次统计的最长天数：再长就属于年度报表，不属于对话里的问题。 */
    private static final int MAX_DAYS = 92;

    /** 未指定时的默认区间。 */
    private static final int DEFAULT_DAYS = 30;

    /** 周趋势最多给几段（再多就是明细，不是趋势）。 */
    private static final int MAX_BUCKETS = 6;

    private final GlucoseRecordService glucoseRecordService;

    private final GlucosePeriodService glucosePeriodService;

    private final HealthProfileService healthProfileService;

    private final JsonUtils jsonUtils;

    @Tool(
            name = "query_glucose_stats",
            description =
                    "查询当前用户一段时间内血糖的统计结论：记录条数/天数、平均值、最高最低、波动、"
                            + "达标率（偏低/达标/偏高各占比）、分时段表现、最近几周的走势。"
                            + "用户问「这两个月控制得怎么样」「达标率多少」「稳不稳定」「整体情况」时使用。"
                            + "days 省略按最近 30 天，最长 92 天；本工具不返回逐条明细——需要具体某天某次的值请用 query_glucose_range。")
    public String queryGlucoseStats(
            @ToolParam(
                            required = false,
                            description = "最近多少天（含今天），默认 30，最长 92。用户说「这三个月」用 90、「最近两周」用 14")
                    Integer days,
            ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);

        int span = days == null ? DEFAULT_DAYS : days;
        if (span < 1 || span > MAX_DAYS) {
            return error("days 需在 1~" + MAX_DAYS + " 之间");
        }

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);

        List<GlucoseRecord> rows;
        Map<String, GlucoseNode> nodes;
        try {
            rows = new ArrayList<>(glucoseRecordService.listRange(userId, from, today));
            nodes = glucosePeriodService.nodeMap();
        } catch (RuntimeException e) {
            log.error("工具 query_glucose_stats 查询失败 userId={} {}~{}", userId, from, today, e);
            return error("查询血糖记录时出错，请稍后重试");
        }

        // 同样的输入给同样的输出：查询实现不保证顺序，这里定死
        rows.sort(
                Comparator.comparing(GlucoseRecord::getRecordDate)
                        .thenComparing(
                                GlucoseRecord::getNodeCode,
                                Comparator.nullsLast(Comparator.naturalOrder())));

        GlucoseRates.Targets targets = resolveTargets(userId);

        List<BigDecimal> values = new ArrayList<>(rows.size());
        List<Boolean> meals = new ArrayList<>(rows.size());
        for (GlucoseRecord row : rows) {
            values.add(row.getGlucoseValue());
            meals.add(isMeal(nodes.get(row.getNodeCode())));
        }

        GlucoseRates.Tally tally = GlucoseRates.tally(values, meals, targets);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", today.toString());
        result.put("days", span);
        result.put("recordCount", rows.size());
        result.put("recordedDays", rows.stream().map(GlucoseRecord::getRecordDate).distinct().count());
        result.put("missingDays", Math.max(0, span - (int) rows.stream().map(GlucoseRecord::getRecordDate).distinct().count()));

        Map<String, Object> targetMap = new LinkedHashMap<>();
        targetMap.put("fasting", targets.fasting());
        targetMap.put("postprandial", targets.postprandial());
        targetMap.put("low", GlucoseRates.LOW_LIMIT);
        targetMap.put("source", targets.fromProfile() ? "profile" : "default");
        result.put("targets", targetMap);

        result.put("avg", GlucoseRates.mean(values));
        result.put("min", values.stream().filter(java.util.Objects::nonNull).min(BigDecimal::compareTo).orElse(null));
        result.put("max", values.stream().filter(java.util.Objects::nonNull).max(BigDecimal::compareTo).orElse(null));
        result.put("cv", GlucoseRates.coefficientOfVariation(values));
        result.put("inRange", rateMap(tally.inRange(), tally.inRangeRate()));
        result.put("high", rateMap(tally.high(), tally.highRate()));
        result.put("lowCount", rateMap(tally.low(), tally.lowRate()));
        result.put("bySlot", bySlot(rows, nodes, targets));
        result.put("buckets", buckets(rows, nodes, targets, from, today));

        if (rows.isEmpty()) {
            result.put("hint", "最近 " + span + " 天没有任何血糖记录。先确认用户是否还没测/没录，不要用零值编造结论。");
        } else {
            result.put("hint", "本工具只给聚合结论，不含逐条明细；用户要看具体某天的数值时改用 query_glucose_range。"
                    + "若 targets.source=default，回答里要说明「按通用目标值」，不要当成用户的医嘱。");
        }

        log.info(
                "工具 query_glucose_stats 执行 userId={} {}~{} 记录 {} 条 达标率={}",
                userId, from, today, rows.size(), tally.inRangeRate());
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

    private List<Map<String, Object>> bySlot(
            List<GlucoseRecord> rows, Map<String, GlucoseNode> nodes, GlucoseRates.Targets targets) {
        Map<String, List<GlucoseRecord>> grouped = new LinkedHashMap<>();
        for (GlucoseRecord row : rows) {
            if (row.getNodeCode() == null) {
                continue;
            }
            grouped.computeIfAbsent(row.getNodeCode(), key -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> items = new ArrayList<>(grouped.size());
        grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            List<GlucoseRecord> slotRows = entry.getValue();
                            List<BigDecimal> slotValues = slotRows.stream().map(GlucoseRecord::getGlucoseValue).toList();
                            List<Boolean> slotMeals = slotRows.stream().map(r -> isMeal(nodes.get(r.getNodeCode()))).toList();
                            GlucoseRates.Tally slotTally = GlucoseRates.tally(slotValues, slotMeals, targets);
                            GlucoseNode node = nodes.get(entry.getKey());
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("slot", node == null ? entry.getKey() : node.getNodeName());
                            item.put("count", slotTally.total());
                            item.put("avg", GlucoseRates.mean(slotValues));
                            item.put("inRangeRate", slotTally.inRangeRate());
                            items.add(item);
                        });
        return items;
    }

    /** 最近几周（最多 6 段，从近到远切成 7 天一段），给出每周的条数、均值与达标率。 */
    private List<Map<String, Object>> buckets(
            List<GlucoseRecord> rows,
            Map<String, GlucoseNode> nodes,
            GlucoseRates.Targets targets,
            LocalDate from,
            LocalDate to) {

        List<Map<String, Object>> items = new ArrayList<>();
        LocalDate end = to;
        for (int i = 0; i < MAX_BUCKETS && !end.isBefore(from); i++) {
            LocalDate start = end.minusDays(6);
            if (start.isBefore(from)) {
                start = from;
            }
            final LocalDate bucketStart = start;
            final LocalDate bucketEnd = end;
            List<GlucoseRecord> slice =
                    rows.stream()
                            .filter(r -> r.getRecordDate() != null)
                            .filter(r -> !r.getRecordDate().isBefore(bucketStart) && !r.getRecordDate().isAfter(bucketEnd))
                            .toList();
            if (!slice.isEmpty()) {
                List<BigDecimal> sliceValues = slice.stream().map(GlucoseRecord::getGlucoseValue).toList();
                List<Boolean> sliceMeals = slice.stream().map(r -> isMeal(nodes.get(r.getNodeCode()))).toList();
                GlucoseRates.Tally sliceTally = GlucoseRates.tally(sliceValues, sliceMeals, targets);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("from", bucketStart.toString());
                item.put("to", bucketEnd.toString());
                item.put("count", sliceTally.total());
                item.put("avg", GlucoseRates.mean(sliceValues));
                item.put("inRangeRate", sliceTally.inRangeRate());
                items.add(item);
            }
            end = start.minusDays(1);
        }
        // 返回统一按时间正序，模型读起来与「越来越好/越来越差」的叙述方向一致
        List<Map<String, Object>> ordered = new ArrayList<>(items);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private static Map<String, Object> rateMap(long count, BigDecimal rate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", count);
        map.put("rate", rate);
        return map;
    }

    private static boolean isMeal(GlucoseNode node) {
        return GlucosePeriodService.isMeal(node);
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
