package com.threadfit.glucopilot.prediction.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.prediction.dto.PredictionDtos;
import com.threadfit.glucopilot.prediction.service.PredictionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端餐后血糖预测入口（两段式：现状预测 / 建议文案 / 按建议量再预测）。
 * 仅一个纯计算接口 POST /api/v1/predictions/meal-glucose：零持久化、无幂等键（纯读本地健康参数，
 * 不落库即无重放副作用）；userId 由请求上下文 {@link RequestContext} 解析，不从请求体读取。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;

    /** 预测餐后血糖。校验入参；userId 统一取自当前登录上下文，返回统一 ApiResponse 包装。 */
    @PostMapping("/predictions/meal-glucose")
    public ApiResponse<PredictionDtos.PredictResult> predict(@Valid @RequestBody PredictionDtos.PredictRequest request) {
        long startNanos = System.nanoTime();
        Long userId = RequestContext.requireUserId();
        log.info("prediction request: requestId={}, userId={}, body={}", MDC.get("requestId"), userId, request);
        PredictionDtos.PredictResult result = predictionService.predict(userId, request);
        log.info("prediction done: requestId={}, userId={}, costMs={}, result={}", MDC.get("requestId"), userId,
                (System.nanoTime() - startNanos) / 1_000_000, result);
        return ApiResponse.ok(result);
    }
}