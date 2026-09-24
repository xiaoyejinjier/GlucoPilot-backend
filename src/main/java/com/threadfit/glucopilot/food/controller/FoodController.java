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

/** C 端营养库：食物分类、检索与营养信息。分类为全量返回，不套分页结构。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FoodController {

    private final FoodService foodService;

    @GetMapping("/food-categories")
    public ApiResponse<List<FoodDtos.FoodCategoryItem>> foodCategories() {
        return ApiResponse.ok(foodService.listFoodCategories());
    }

    @GetMapping("/foods")
    public ApiResponse<PageResult<FoodDtos.FoodItem>> foods(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer categoryCode,
            @RequestParam(required = false) String keyword,
            @Valid PageQuery query) {
        return ApiResponse.ok(foodService.pageFoods(categoryId, categoryCode, keyword, query));
    }

    @GetMapping("/foods/{id:\\d+}")
    public ApiResponse<FoodDtos.FoodItem> foodDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(foodService.getFoodDetail(id));
    }
}
