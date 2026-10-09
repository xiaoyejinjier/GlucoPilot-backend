package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 饮食记录查询工具。
 * <p>
 * <b>为什么只支持"最近 N 天"而不是任意日期区间</b>：饮食记录每天可能有多餐、每餐还带明细，
 * 返回体比血糖大得多。把窗口限制在 7 天，是为了避免一次工具调用就把上下文撑爆。
 * 需要看更早的数据时，模型应当引导用户缩小范围。
 * <p>
 * <b>裁剪掉哪些字段（都是实测 DTO 里的）</b>：
 * {@code id}（内部主键）、{@code mediaId}/{@code mediaUrl}（模型看不到图片，给了只会浪费 token）、
 * {@code recordedAt}（精度到毫秒，对回答无意义）、{@code items}（食物明细列表，通常 {@code foodsText} 已足够概括）。
 * <p>
 * 保留 {@code supplementText}：那是用户自己补的上下文（如"加班没吃午饭"），对分析有价值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MealRecordTools {

    /** 单次最多回看多少天。 */
    private static final int MAX_DAYS = 7;

    private final MealRecordService mealRecordService;
    private final GlucosePeriodService glucosePeriodService;
    private final JsonUtils jsonUtils;

    @Tool(name = "query_meal_records",
            description = "查询当前用户最近几天的饮食记录：每餐吃了什么、估算碳水克数、预计餐后血糖峰值。"
                    + "用户问「我昨天吃了什么」「最近饮食怎么样」「哪一餐碳水高」时使用。"
                    + "days 表示最近多少天（含今天），最多 7 天；返回里带日期，可据此判断「昨天」是哪一条。")
    public String queryMealRecords(
            @ToolParam(required = false, description = "最近多少天（含今天），最多 7；省略表示 3 天")
            Integer days,
            ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);

        int span = (days == null || days <= 0) ? 3 : days;
        if (span > MAX_DAYS) {
            return error("单次最多查询最近 " + MAX_DAYS + " 天的饮食记录，请缩小范围（或让用户指定更近的日期）");
        }

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);
        Map<String, GlucoseNode> nodes = glucosePeriodService.nodeMap();

        List<Map<String, Object>> meals = new ArrayList<>();
        int recordedDays = 0;
        try {
            for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
                MealVos.DayMeals day = mealRecordService.listDay(userId, date);
                if (day == null || day.getMeals() == null || day.getMeals().isEmpty()) {
                    continue;
                }
                recordedDays++;
                for (MealVos.MealDetail meal : day.getMeals()) {
                    GlucoseNode node = nodes.get(meal.getNodeCode());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", String.valueOf(date));
                    item.put("meal", node == null ? meal.getNodeCode() : node.getNodeName());
                    item.put("foodsText", meal.getFoodsText());
                    item.put("carbsG", meal.getCarbsG());
                    item.put("predictNow", meal.getPredictNow());
                    item.put("hungerLevel", meal.getHungerLevel());
                    item.put("supplementText", meal.getSupplementText());
                    meals.add(item);
                }
            }
        } catch (RuntimeException e) {
            log.error("工具 query_meal_records 查询失败 userId={} {}~{}", userId, from, today, e);
            return error("查询饮食记录时出错，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", today.toString());
        result.put("recordedDays", recordedDays);
        result.put("count", meals.size());
        result.put("meals", meals);
        log.info("工具 query_meal_records 执行 userId={} {}~{} 记录天数={} 餐次={}",
                userId, from, today, recordedDays, meals.size());
        return jsonUtils.toJson(result);
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
