package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.glucose.record.GlucoseRecordService;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 血糖查询工具（AI 模块的第一个 Function Calling 工具）。
 * <p>
 * <b>设计约束（三条，都是踩过或差点踩到的）</b>：
 * <ol>
 *   <li><b>用户身份来自 {@link ToolContext}，不是方法参数</b>。方法签名里凡是模型能填的参数，
 *       就等于把越权入口交给了模型。</li>
 *   <li><b>返回值是裁剪后的 JSON，不是实体/DTO</b>。直接把 {@code GlucoseRecord} 交给模型会带上
 *       userId、逻辑删除标记等无关字段——既浪费 token，也是不必要的信息暴露。</li>
 *   <li><b>输入类错误走返回值而不是抛异常</b>。日期格式错、区间过长这类问题模型可以自行修正，
 *       返回 {@code {"error": ...}} 让它重试；只有"缺 userId"这种代码缺陷才抛异常。</li>
 * </ol>
 * <p>
 * <b>为什么必须支持相对时间（{@code days}）</b>：实测发现<b>模型不知道今天是几号</b>——
 * 用户问"我这周血糖怎么样"时，模型无法把"这周"换算成日期，于是它不调用工具、
 * 反过来问用户"请告诉我具体日期范围"。这在真实使用里是很差的体验。
 * 解法是让<b>知道日期的服务端</b>承担换算：模型只需给 {@code days=7}。
 * <p>
 * <b>为什么要自己限制区间与条数</b>：模型很容易写出"查一年"这种调用，而工具结果会整体进入
 * 下一轮上下文。不设上限的话，一次工具调用就能把上下文撑爆（并且按 token 计费）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlucoseQueryTools {

    /** 单次查询的最大天数。超过就让模型缩小范围，而不是默默截断。 */
    private static final int MAX_RANGE_DAYS = 31;

    /** 单次返回给模型的最大条数；超出时置 {@code truncated=true} 明确告知。 */
    private static final int MAX_ROWS = 60;

    /** 什么都没给时的默认区间（最近 7 天），并在结果里明确标注"这是假设的区间"。 */
    private static final int DEFAULT_DAYS = 7;

    private final GlucoseRecordService glucoseRecordService;
    private final GlucosePeriodService glucosePeriodService;
    /** 聚合计算（按天趋势、平均值）：它收的是"已查出的记录列表"，不是 userId，因此只能在工具内部复用。 */
    private final GlucoseTrendService glucoseTrendService;
    private final JsonUtils jsonUtils;

    @Tool(name = "query_glucose_range",
            description = "查询当前用户在指定时间范围内的血糖记录，返回每条记录的日期、时段与血糖值(mmol/L)。"
                    + "用户询问自己的血糖情况时使用。优先用 days 表达相对时间（如「这周」用 days=7、「最近三天」用 days=3）；"
                    + "只有用户明确说了日期，才用 fromDate/toDate。")
    public String queryGlucoseRange(
            @ToolParam(required = false, description = "起始日期（含），格式 yyyy-MM-dd；仅在用户明确给出日期时使用")
            String fromDate,
            @ToolParam(required = false, description = "结束日期（含），格式 yyyy-MM-dd；仅在用户明确给出日期时使用")
            String toDate,
            @ToolParam(required = false, description = "最近多少天（含今天）。用户说「这周」「最近」而没给具体日期时用它，"
                    + "例如「这周」=7、「最近三天」=3。若同时给了 fromDate/toDate，以日期为准")
            Integer days,
            ToolContext toolContext) {

        // 用户身份只能来自服务端注入，模型不可见（见 AiToolContext 注释）
        Long userId = AiToolContext.requireUserId(toolContext);

        LocalDate today = LocalDate.now();
        LocalDate from;
        LocalDate to;
        String rangeNote = null;

        boolean hasDates = hasText(fromDate) || hasText(toDate);
        if (hasDates) {
            if (!hasText(fromDate) || !hasText(toDate)) {
                return error("fromDate 与 toDate 必须同时给出；或改用 days 表示最近多少天");
            }
            try {
                from = LocalDate.parse(fromDate.trim());
                to = LocalDate.parse(toDate.trim());
            } catch (RuntimeException e) {
                return error("日期格式必须是 yyyy-MM-dd，例如 2026-09-22");
            }
        } else if (days != null && days > 0) {
            from = today.minusDays(days - 1L);
            to = today;
            rangeNote = "最近 " + days + " 天（截至 " + today + "）";
        } else {
            from = today.minusDays(DEFAULT_DAYS - 1L);
            to = today;
            rangeNote = "未指定时间范围，已默认按最近 " + DEFAULT_DAYS + " 天查询（截至 " + today + "）";
        }

        if (from.isAfter(to)) {
            return error("起始日期不能晚于结束日期");
        }
        long spanDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (spanDays > MAX_RANGE_DAYS) {
            return error("单次查询区间最长 " + MAX_RANGE_DAYS + " 天，请缩小范围后重试");
        }

        List<GlucoseRecord> rows;
        Map<String, GlucoseNode> nodes;
        try {
            rows = new ArrayList<>(glucoseRecordService.listRange(userId, from, to));
            nodes = glucosePeriodService.nodeMap();
        } catch (RuntimeException e) {
            // 数据源异常不该把整轮对话打成 500：记 ERROR 给运维，返回一句可读的错误给模型，
            // 让模型能对用户如实说明"暂时查不到"。与 writeBackCaption 的处理原则一致——
            // 辅助环节失败不应让已经成功的回答变成错误。
            log.error("工具 query_glucose_range 查询失败 userId={} {}~{}", userId, from, to, e);
            return error("查询血糖记录时出错，请稍后重试");
        }

        // 查询实现不保证顺序，这里显式排序，保证同样的问题得到同样的答案（便于测试与复现）
        rows.sort(Comparator.comparing(GlucoseRecord::getRecordDate)
                .thenComparing(GlucoseRecord::getNodeCode, Comparator.nullsLast(Comparator.naturalOrder())));

        boolean truncated = rows.size() > MAX_ROWS;
        List<GlucoseRecord> limited = truncated ? rows.subList(0, MAX_ROWS) : rows;

        List<Map<String, Object>> items = new ArrayList<>(limited.size());
        for (GlucoseRecord row : limited) {
            GlucoseNode node = nodes.get(row.getNodeCode());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", String.valueOf(row.getRecordDate()));
            item.put("node", row.getNodeCode());
            // 把 nodeCode 翻译成中文：模型不一定知道 before_breakfast 是什么
            item.put("nodeName", node == null ? row.getNodeCode() : node.getNodeName());
            item.put("value", row.getGlucoseValue());
            // 复用 GlucosePeriodService 的判定，避免在 AI 模块再维护一份"什么算餐点时段"
            item.put("isMeal", GlucosePeriodService.isMeal(node));
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        if (rangeNote != null) {
            // 明确告知"区间是推断来的"，模型才能在回答里说清口径，而不是假装用户指定过
            result.put("rangeNote", rangeNote);
        }
        result.put("count", rows.size());
        result.put("truncated", truncated);

        // 聚合段：让「这段时间怎么样」这类问题不必靠 60 条明细去心算。
        // 这里复用的都是收「记录列表」的内部计算函数——模型给不出这种参数，
        // 但工具内部可以组合它们，这就是"工具是组合层、不是门面转发层"的具体做法。
        //
        // 单独兜一层：明细已经查到了，聚合只是锦上添花，它出问题不应让整轮对话变成错误。
        try {
            result.put("summary", summaryOf(rows));
            result.put("daily", dailyOf(rows, from, to));
            result.put("slots", slotsOf(rows));
        } catch (RuntimeException e) {
            log.warn("血糖聚合摘要计算失败，本次仅返回明细 userId={} {}~{}", userId, from, to, e);
        }
        result.put("items", items);
        log.info("工具 query_glucose_range 执行 userId={} {}~{} 命中 {} 条 trunc={}",
                userId, from, to, rows.size(), truncated);
        return jsonUtils.toJson(result);
    }

    /** 整体概览：平均值、最高/最低、覆盖天数。 */
    private static Map<String, Object> summaryOf(List<GlucoseRecord> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("avg", GlucoseTrendService.average(rows));
        summary.put("recordedDays", rows.stream().map(GlucoseRecord::getRecordDate).distinct().count());
        rows.stream().map(GlucoseRecord::getGlucoseValue).filter(Objects::nonNull)
                .max(BigDecimal::compareTo).ifPresent(v -> summary.put("highest", v));
        rows.stream().map(GlucoseRecord::getGlucoseValue).filter(Objects::nonNull)
                .min(BigDecimal::compareTo).ifPresent(v -> summary.put("lowest", v));
        return summary;
    }

    /** 按天趋势。无记录的天会以 count=0 出现，便于模型判断"哪几天没测"。 */
    private List<Map<String, Object>> dailyOf(List<GlucoseRecord> rows, LocalDate from, LocalDate to) {
        List<GlucoseDtos.TrendPoint> points = glucoseTrendService.dailySeries(rows, from, to);
        if (points == null) {
            return List.of();
        }
        return points.stream()
                .map(point -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", String.valueOf(point.getDate()));
                    item.put("avg", point.getAvg());
                    item.put("count", point.getCount());
                    return item;
                })
                .toList();
    }

    /**
     * 分时段聚合。
     * <p>
     * 刻意<b>不返回 {@code SlotStat.pct}</b>：它的定义是「相对记录最多时段的百分比，用于端上进度条」，
     * 与"达标率"无关。回给模型会被误读成达标率，进而给出错误结论——宁可不给，
     * 也不能给一个会被误解的数字。
     */
    private List<Map<String, Object>> slotsOf(List<GlucoseRecord> rows) {
        List<GlucoseDtos.SlotStat> stats = glucosePeriodService.slotStats(rows);
        if (stats == null) {
            return List.of();
        }
        return stats.stream()
                .map(stat -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("slot", stat.getName());
                    item.put("avg", stat.getAvg());
                    item.put("count", stat.getCount());
                    return item;
                })
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 输入类错误走返回值，让模型有机会自行修正参数后重试。 */
    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
