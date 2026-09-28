package com.threadfit.glucopilot.meal.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.meal.dto.MealDtos;
import com.threadfit.glucopilot.meal.service.MealRecordService;
import com.threadfit.glucopilot.meal.vo.MealVos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 饮食记录（C 端）：保存（覆盖写入）、按日查询、删除。
 */
@RestController
@RequestMapping("/api/v1/meal-records")
@RequiredArgsConstructor
public class MealRecordController {

    private final MealRecordService mealRecordService;

    @PostMapping
    public ApiResponse<MealVos.SaveResult> save(@Valid @RequestBody MealDtos.SaveRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mealRecordService.save(userId, request));
    }

    @GetMapping
    public ApiResponse<MealVos.DayMeals> listDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mealRecordService.listDay(userId, recordDate));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        Long userId = RequestContext.requireUserId();
        mealRecordService.remove(userId, id);
        return ApiResponse.ok();
    }
}
