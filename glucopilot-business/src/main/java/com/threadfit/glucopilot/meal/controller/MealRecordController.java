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
 * 饮食记录（C 端）- REST 控制器。
 * <p>接口表：
 * <ul>
 *   <li>POST /api/v1/meal-records —— 按 (recordDate, nodeCode) 覆盖保存，同日同餐点命中即 update，否则 insert；</li>
 *   <li>GET /api/v1/meal-records?recordDate= —— 按日查询当前用户当天三餐明细；</li>
 *   <li>DELETE /api/v1/meal-records/{id} —— 逻辑删除主记录及其明细。</li>
 * </ul>
 * 用户身份统一取自请求上下文（RequestContext），除主记录删除外均以 userId 隔离数据。
 */
@RestController
@RequestMapping("/api/v1/meal-records")
@RequiredArgsConstructor
public class MealRecordController {

    private final MealRecordService mealRecordService;

    /** 覆盖保存：同日同餐点幂等写入，返回主记录 id 与是否新建。 */
    @PostMapping
    public ApiResponse<MealVos.SaveResult> save(@Valid @RequestBody MealDtos.SaveRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mealRecordService.save(userId, request));
    }

    /** 按日查询：查当天全部餐点（按 nodeCode 排序），含前端展示所需照片 URL。 */
    @GetMapping
    public ApiResponse<MealVos.DayMeals> listDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mealRecordService.listDay(userId, recordDate));
    }

    /** 删除：逻辑删除主记录，并级联逻辑删除其明细。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        Long userId = RequestContext.requireUserId();
        mealRecordService.remove(userId, id);
        return ApiResponse.ok();
    }
}
