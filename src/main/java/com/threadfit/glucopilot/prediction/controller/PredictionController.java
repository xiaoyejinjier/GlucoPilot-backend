package com.threadfit.glucopilot.prediction.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.prediction.dto.PredictionDtos;
import com.threadfit.glucopilot.prediction.service.PredictionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端餐后血糖预测（两段式：现状预测 / 建议文案 / 按建议量再预测）。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;

    @PostMapping("/predictions/meal-glucose")
    public ApiResponse<PredictionDtos.PredictResult> predict(@Valid @RequestBody PredictionDtos.PredictRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(predictionService.predict(userId, request));
    }
}