package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.service.FoodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 食物营养库查询工具（批量）。
 * <p>
 * <b>为什么入参是数组而不是一个名字</b>：用户问「米饭、红烧肉、青菜各多少碳水」时，单个入参的工具会被调三次——
 * 三次工具预算（{@code spring.ai.tools.limits.max-total-tool-calls}，当前 8）、三条审计记录、
 * 三份结果进上下文，而去重器帮不上忙（入参不同）。一次传入把这三样都省掉。
 * 反过来，<b>每个名字只给 1~2 条候选</b>（默认 2）：5 个名字 × 5 条候选比调 5 次还费 token。
 * <p>
 * <b>为什么这个方法不需要 {@code ToolContext}</b>：食物营养库是<b>全量公共数据</b>，与用户无关，
 * 不存在越权问题。刻意不声明 {@code ToolContext} 参数——声明了反而会误导后来者以为它读用户数据。
 * （其余工具都声明了它，因为它们的第一个参数都是 {@code userId}。）
 * <p>
 * <b>裁剪</b>：只回名称、分类、每 100 克碳水/热量、GI 值。
 * {@code id}/{@code categoryId}/{@code categoryCode}/{@code imageUrl} 对回答无用，回传只会白花 token。
 * <p>
 * <b>失败不整体炸</b>：某个名字查不到、太长、或该名字查询报错，都只进 {@code notFound}（带原因），
 * 其余名字照常返回。整次调用失败会让模型白烧一轮重新组织入参。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoodNutritionTools {

    /** 单次最多查几个名字。超过的部分截断并在返回里说明，不报错（报错等于白烧一轮）。 */
    private static final int MAX_NAMES = 5;

    /** 每个名字默认回几条候选。 */
    private static final int DEFAULT_MATCHES = 2;

    /** 每个名字最多回几条候选。 */
    private static final int MAX_MATCHES = 3;

    /** 单个名字的最大长度；再长通常意味着模型把整句话塞进来了。 */
    private static final int MAX_NAME_CHARS = 32;

    /**
     * 模型有时会把多个名字塞进一个字符串（{@code "米饭、红烧肉"}）。
     * 与其让它整条查不到，不如拆开——这个容错比重试一次便宜得多。
     */
    private static final Pattern SEPARATORS = Pattern.compile("[、,，/]");

    private final FoodService foodService;
    private final JsonUtils jsonUtils;

    @Tool(name = "lookup_food_nutrition",
            description = "按名称查询食物营养库，一次可查 1~5 个食物。返回每个食物每 100 克的碳水克数、热量与 GI 值。"
                    + "用户问「XX 的碳水多少」「XX 升糖快吗」「XX 能不能吃」时使用，再结合用户的血糖记录给出建议。"
                    + "用户问到一顿饭或一天里的多种食物时，必须把它们一次放进 foodNames 数组，不要分多次调用。")
    public String lookupFoodNutrition(
            @ToolParam(description = "食物名称数组，例如 [\"米饭\",\"红烧肉\",\"青菜\"]。只有一个也用数组，最多 5 个")
            List<String> foodNames,
            @ToolParam(required = false, description = "每个名字返回几条候选，默认 2，最大 3")
            Integer matchesPerName) {

        List<String> requested = normalize(foodNames);
        if (requested.isEmpty()) {
            return error("请给出至少一个食物名称，例如 [\"米饭\"]；不要传整句话");
        }

        int matches = clamp(matchesPerName);
        List<String> names = requested.size() > MAX_NAMES
                ? new ArrayList<>(requested.subList(0, MAX_NAMES))
                : requested;
        List<String> ignored = requested.size() > MAX_NAMES
                ? new ArrayList<>(requested.subList(MAX_NAMES, requested.size()))
                : List.of();

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> notFound = new ArrayList<>();
        for (String name : names) {
            if (name.length() > MAX_NAME_CHARS) {
                notFound.add(miss(name, "名称过长，请只给出食物名称（如「米饭」），不要传整句话"));
                continue;
            }
            Map<String, Object> item = lookupOne(name, matches, notFound);
            if (item != null) {
                items.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", requested.size());
        result.put("queried", names.size());
        result.put("found", items.size());
        result.put("items", items);
        if (!notFound.isEmpty()) {
            result.put("notFound", notFound);
        }
        if (!ignored.isEmpty()) {
            result.put("ignored", ignored);
        }
        result.put("hint", hint(items.isEmpty(), ignored));
        log.info("工具 lookup_food_nutrition 执行 请求 {} 个、命中 {} 个、未命中 {} 个、忽略 {} 个",
                names.size(), items.size(), notFound.size(), ignored.size());
        return jsonUtils.toJson(result);
    }

    /**
     * 查一个名字。
     *
     * @return 命中时返回该项；未命中时把原因写进 {@code notFound} 并返回 {@code null}
     */
    private Map<String, Object> lookupOne(String name, int matches, List<Map<String, Object>> notFound) {
        PageResult<FoodDtos.FoodItem> page;
        try {
            PageQuery query = new PageQuery();
            query.setPageSize(matches);
            page = foodService.pageFoods(null, null, name, query);
        } catch (RuntimeException e) {
            log.error("工具 lookup_food_nutrition 查询失败 name={}", name, e);
            notFound.add(miss(name, "查询食物营养库时出错"));
            return null;
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        if (page != null && page.getRecords() != null) {
            for (FoodDtos.FoodItem food : page.getRecords()) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("foodName", food.getFoodName());
                candidate.put("categoryName", food.getCategoryName());
                candidate.put("carbsPer100g", food.getCarbsPer100g());
                candidate.put("caloriesPer100g", food.getCaloriesPer100g());
                candidate.put("giValue", food.getGiValue());
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            notFound.add(miss(name, "食物库中没有匹配项，可换一个更常见的名称"));
            return null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("total", page.getTotal());
        item.put("matches", candidates);
        return item;
    }

    private static Map<String, Object> miss(String name, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("reason", reason);
        return map;
    }

    /**
     * 拆分、去重、去空白。数组元素里带顿号/逗号的会被拆开——模型经常这么传。
     */
    private static List<String> normalize(List<String> foodNames) {
        if (foodNames == null || foodNames.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : foodNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String part : SEPARATORS.split(raw)) {
                String trimmed = part.trim().replaceAll("\\s+", " ");
                if (!trimmed.isEmpty()) {
                    // 用大写做去重键：ASCII 名称不区分大小写，中文不受影响
                    seen.add(trimmed);
                }
            }
        }
        // 二次去重：忽略大小写
        List<String> result = new ArrayList<>(seen.size());
        Set<String> keys = new LinkedHashSet<>();
        for (String name : seen) {
            if (keys.add(name.toUpperCase(Locale.ROOT))) {
                result.add(name);
            }
        }
        return result;
    }

    private static int clamp(Integer matchesPerName) {
        if (matchesPerName == null) {
            return DEFAULT_MATCHES;
        }
        return Math.max(1, Math.min(MAX_MATCHES, matchesPerName));
    }

    private static String hint(boolean nothingFound, List<String> ignored) {
        if (nothingFound) {
            return "这些名字在食物库里都没有匹配：可换更常见的名称再试一次（一次仍可传多个），或提示用户手动记录。";
        }
        if (!ignored.isEmpty()) {
            return "已查询前 " + MAX_NAMES + " 个食物；" + ignored + " 还没查，需要的话再调一次（每次最多 "
                    + MAX_NAMES + " 个）。";
        }
        return "可以回答用户了。";
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
