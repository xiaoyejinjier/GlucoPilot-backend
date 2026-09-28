package com.threadfit.glucopilot.food.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.food.dto.AdminFoodDtos;
import com.threadfit.glucopilot.food.service.AdminFoodService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** B 端营养库管理。 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class AdminFoodController {

    private final AdminFoodService adminFoodService;

    @GetMapping("/food-categories")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<List<AdminFoodDtos.FoodCategoryItem>> listCategories() {
        return ApiResponse.ok(adminFoodService.listCategories());
    }

    @PostMapping("/food-categories")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodCategoryItem> createCategory(
            @Valid @RequestBody AdminFoodDtos.FoodCategorySaveRequest request) {
        return ApiResponse.ok(adminFoodService.saveCategory(request));
    }

    @PutMapping("/food-categories/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodCategoryItem> updateCategory(
            @PathVariable Long id, @Valid @RequestBody AdminFoodDtos.FoodCategorySaveRequest request) {
        request.setId(id);
        return ApiResponse.ok(adminFoodService.saveCategory(request));
    }

    @DeleteMapping("/food-categories/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<Void> removeCategory(@PathVariable Long id) {
        adminFoodService.removeCategory(id);
        return ApiResponse.ok();
    }

    @GetMapping("/foods")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<List<AdminFoodDtos.FoodItem>> listFoods() {
        return ApiResponse.ok(adminFoodService.listFoods());
    }

    @PostMapping("/foods")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodItem> createFood(@Valid @RequestBody AdminFoodDtos.FoodSaveRequest request) {
        return ApiResponse.ok(adminFoodService.saveFood(request));
    }

    @PutMapping("/foods/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodItem> updateFood(
            @PathVariable Long id, @Valid @RequestBody AdminFoodDtos.FoodSaveRequest request) {
        request.setId(id);
        return ApiResponse.ok(adminFoodService.saveFood(request));
    }

    @DeleteMapping("/foods/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<Void> removeFood(@PathVariable Long id) {
        adminFoodService.removeFood(id);
        return ApiResponse.ok();
    }
}
