package com.threadfit.glucopilot.meal.suitability.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.meal.suitability.dto.MealSuitabilityDtos;
import com.threadfit.glucopilot.meal.suitability.service.MealSuitabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 餐食适食分析（C 端）- REST 控制器。
 * <p>接口表：
 * <ul>
 *   <li>POST /api/v1/meal-suitability/preview —— 纯规则预览；不写库、不调大模型、不读血糖，同输入同版本结果稳定。</li>
 * </ul>
 * 用户身份统一取自请求上下文（RequestContext）；本期 userId 不参与判定，但契约要求鉴权。
 */
@RestController
@RequestMapping("/api/v1/meal-suitability")
@RequiredArgsConstructor
public class MealSuitabilityController {

    private final MealSuitabilityService mealSuitabilityService;

    /** 预览：按规则版本对食物项逐项判定，整餐取最差项。 */
    @PostMapping("/preview")
    public ApiResponse<MealSuitabilityDtos.PreviewResponse> preview(
            @Valid @RequestBody MealSuitabilityDtos.PreviewRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(mealSuitabilityService.preview(userId, request));
    }
}
