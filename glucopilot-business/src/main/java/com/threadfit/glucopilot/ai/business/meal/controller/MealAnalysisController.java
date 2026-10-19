package com.threadfit.glucopilot.ai.business.meal.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.threadfit.glucopilot.ai.business.meal.dto.MealAnalysisDtos;
import com.threadfit.glucopilot.ai.business.meal.service.MealAnalysisService;

@RestController
@RequiredArgsConstructor
public class MealAnalysisController {
    private final MealAnalysisService service;

    @PostMapping("/api/v1/meal-analysis")
    public ApiResponse<MealAnalysisDtos.Result> analyze(@Valid @RequestBody MealAnalysisDtos.Request request) {
        return ApiResponse.ok(service.analyze(RequestContext.requireUserId(), request));
    }
}
