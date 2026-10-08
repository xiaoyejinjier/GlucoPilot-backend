package com.threadfit.glucopilot.food.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.food.dto.FoodDtos;
import com.threadfit.glucopilot.food.service.FoodService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端食品营养库接口层。
 *
 * <p>Controller 的职责是接收前端传入的路径参数、查询参数和分页参数，
 * 将参数交给 {@link FoodService} 处理，再把 Service 返回的数据包装成统一的
 * {@link ApiResponse}。Controller 不直接操作数据库，也不编写具体业务规则。</p>
 */
@RestController
// 小程序端业务接口统一使用 /api/v1 前缀。
@RequestMapping("/api/v1")
// Lombok 根据 final 字段生成构造方法，由 Spring 注入 FoodService。
@RequiredArgsConstructor
public class FoodController {

    /** 用户端食品查询业务服务。 */
    private final FoodService foodService;

    /**
     * 查询全部已启用的食品分类。
     *
     * <p>分类数量较少，因此直接返回 List，不使用分页结构。</p>
     */
    @GetMapping("/food-categories")
    public ApiResponse<List<FoodDtos.FoodCategoryItem>> foodCategories() {
        // 调用 Service 查询分类，并包装成项目统一响应格式。
        return ApiResponse.ok(foodService.listFoodCategories());
    }

    /**
     * 分页查询已上架食品。
     *
     * @param categoryId 分类表主键，可不传
     * @param categoryCode 兼容用的大类编码，可不传
     * @param keyword 食品名称关键字，可不传
     * @param query 分页参数，默认第 1 页、每页 20 条
     */
    @GetMapping("/foods")
    public ApiResponse<PageResult<FoodDtos.FoodItem>> foods(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer categoryCode,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        // @Valid 会先校验分页参数；校验通过后再进入 Service 组合查询条件。
        return ApiResponse.ok(foodService.pageFoods(categoryId, categoryCode, keyword, query));
    }

    /**
     * 根据食品 ID 查询一条已上架食品的营养详情。
     * 路径正则 \d+ 限制 id 只能由数字组成。
     */
    @GetMapping("/foods/{id:\\d+}")
    public ApiResponse<FoodDtos.FoodItem> foodDetail(@PathVariable("id") Long id) {
        // 食品不存在或已下架时，由 Service 抛出统一业务异常。
        return ApiResponse.ok(foodService.getFoodDetail(id));
    }
}
