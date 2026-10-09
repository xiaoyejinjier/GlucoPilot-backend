package com.threadfit.glucopilot.ai.tool;

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
import java.util.List;
import java.util.Map;

/**
 * 食物营养库查询工具。
 * <p>
 * <b>为什么这个方法不需要 {@code ToolContext}</b>：食物营养库是<b>全量公共数据</b>，与用户无关，
 * 不存在越权问题。刻意不声明 {@code ToolContext} 参数——声明了反而会误导后来者以为它读用户数据。
 * （其余工具都声明了它，因为它们的第一个参数都是 {@code userId}。）
 * <p>
 * <b>裁剪</b>：只回名称、分类、每 100 克碳水/热量、GI 值。
 * {@code id}/{@code categoryId}/{@code categoryCode}/{@code imageUrl} 对回答无用，回传只会白花 token。
 * <p>
 * <b>为什么限制条数</b>：关键词搜索可能命中很多条，而工具结果会整体进入下一轮上下文。
 * 这里固定取前 5 条，并在返回里给出 {@code total}，让模型知道"还有更多但没全给"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoodNutritionTools {

    /** 单次返回给模型的最大条数。 */
    private static final int MAX_ITEMS = 5;

    private final FoodService foodService;
    private final JsonUtils jsonUtils;

    @Tool(name = "lookup_food_nutrition",
            description = "按名称关键词查询食物营养库，返回每种食物每 100 克的碳水克数、热量与 GI 值。"
                    + "用户问「XX 的碳水多少」「XX 升糖快吗」「XX 能不能吃」时使用，再结合用户的血糖记录给出建议。")
    public String lookupFoodNutrition(
            @ToolParam(description = "食物名称关键词，例如「米饭」「酸奶」「全麦面包」")
            String keyword) {

        if (keyword == null || keyword.isBlank()) {
            return error("请给出食物名称关键词，例如「米饭」");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 32) {
            // 关键词过长通常意味着模型把整句话塞进来了，那搜不到东西；提前拦住给出可读提示
            return error("关键词过长，请只给出食物名称（如「米饭」），不要传整句话");
        }

        PageResult<FoodDtos.FoodItem> page;
        try {
            PageQuery query = new PageQuery();
            query.setPageSize(MAX_ITEMS);
            page = foodService.pageFoods(null, null, trimmed, query);
        } catch (RuntimeException e) {
            log.error("工具 lookup_food_nutrition 查询失败 keyword={}", trimmed, e);
            return error("查询食物营养库时出错，请稍后重试");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        if (page != null && page.getRecords() != null) {
            for (FoodDtos.FoodItem food : page.getRecords()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("foodName", food.getFoodName());
                item.put("categoryName", food.getCategoryName());
                item.put("carbsPer100g", food.getCarbsPer100g());
                item.put("caloriesPer100g", food.getCaloriesPer100g());
                item.put("giValue", food.getGiValue());
                items.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", trimmed);
        result.put("total", page == null ? 0 : page.getTotal());
        result.put("count", items.size());
        if (items.isEmpty()) {
            result.put("hint", "食物库中没有匹配项，可换一个更常见的名称再试，或提示用户手动记录。");
        }
        result.put("items", items);
        log.info("工具 lookup_food_nutrition 执行 keyword={} 命中 {} / 共 {}",
                trimmed, items.size(), page == null ? 0 : page.getTotal());
        return jsonUtils.toJson(result);
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
