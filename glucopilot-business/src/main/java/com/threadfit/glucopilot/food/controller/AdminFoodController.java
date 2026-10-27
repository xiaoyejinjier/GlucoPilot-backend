package com.threadfit.glucopilot.food.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台食品营养库接口层。
 *
 * <p>负责接收管理端提交的分类、食品增删改查请求，使用 {@code @Valid}
 * 校验请求体，并通过 {@code @PreAuthorize} 检查当前运营账号是否拥有
 * 营养库管理权限。实际业务规则交给 {@link AdminFoodService} 完成。</p>
 */
@RestController
// 管理端业务接口统一使用 /admin/v1 前缀。
@RequestMapping("/admin/v1")
// Lombok 生成构造方法，让 Spring 注入 AdminFoodService。
@RequiredArgsConstructor
public class AdminFoodController {

    /** 管理端食品分类和食品维护服务。 */
    private final AdminFoodService adminFoodService;

    /** 查询全部未删除的食品分类，供管理页面展示和下拉框选择。 */
    @GetMapping("/food-categories")
    // 除了登录，还必须拥有 food:library:manage 权限。
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<List<AdminFoodDtos.FoodCategoryItem>> listCategories() {
        return ApiResponse.ok(adminFoodService.listCategories());
    }

    /** 新建食品分类；@Valid 会校验分类名称不能为空。 */
    @PostMapping("/food-categories")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodCategoryItem> createCategory(
            @Valid @RequestBody AdminFoodDtos.FoodCategorySaveRequest request) {
        // 新增请求没有 id，Service 会选择 insert。
        return ApiResponse.ok(adminFoodService.saveCategory(request));
    }

    /** 修改指定食品分类。 */
    @PutMapping("/food-categories/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodCategoryItem> updateCategory(
            @PathVariable Long id, @Valid @RequestBody AdminFoodDtos.FoodCategorySaveRequest request) {
        // 以 URL 中的 id 为准，避免请求体 id 与路径 id 不一致。
        request.setId(id);
        return ApiResponse.ok(adminFoodService.saveCategory(request));
    }

    /**
     * 删除指定食品分类。
     * Service 会先检查分类下是否仍有食品，再执行逻辑删除。
     */
    @DeleteMapping("/food-categories/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<Void> removeCategory(@PathVariable Long id) {
        adminFoodService.removeCategory(id);
        // 删除成功没有额外业务数据，因此返回 data=null 的成功响应。
        return ApiResponse.ok();
    }

    /** 分页查询未删除食品，筛选条件由服务端执行。 */
    @GetMapping("/foods")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<PageResult<AdminFoodDtos.FoodItem>> listFoods(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean pendingReview,
            @Valid PageQuery query) {
        return ApiResponse.ok(adminFoodService.pageFoods(categoryId, keyword, enabled, pendingReview, query));
    }

    /** 新建食品；@Valid 会校验食品名称和分类 ID。 */
    @PostMapping("/foods")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodItem> createFood(@Valid @RequestBody AdminFoodDtos.FoodSaveRequest request) {
        // 新增请求没有 id，Service 会选择 insert。
        return ApiResponse.ok(adminFoodService.saveFood(request));
    }

    /** 修改指定食品。 */
    @PutMapping("/foods/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<AdminFoodDtos.FoodItem> updateFood(
            @PathVariable Long id, @Valid @RequestBody AdminFoodDtos.FoodSaveRequest request) {
        // 以 URL 中的 id 为准，统一新增和修改共用的 Service 方法。
        request.setId(id);
        return ApiResponse.ok(adminFoodService.saveFood(request));
    }

    /** 逻辑删除指定食品。 */
    @DeleteMapping("/foods/{id}")
    @PreAuthorize("@ss.hasPermi('" + PermCodes.FOOD_LIBRARY_MANAGE + "')")
    public ApiResponse<Void> removeFood(@PathVariable Long id) {
        adminFoodService.removeFood(id);
        return ApiResponse.ok();
    }
}
