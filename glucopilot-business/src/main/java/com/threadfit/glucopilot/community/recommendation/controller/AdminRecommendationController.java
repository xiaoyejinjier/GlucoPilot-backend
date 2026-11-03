package com.threadfit.glucopilot.community.recommendation.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.community.recommendation.dto.AdminRecommendationDtos.Report;
import com.threadfit.glucopilot.community.recommendation.service.AdminRecommendationService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/community/recommendations")
@RequiredArgsConstructor
@PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('user:manage')")
public class AdminRecommendationController {
    private final AdminRecommendationService service;

    @GetMapping("/{userId}")
    public ApiResponse<Report> report(@PathVariable long userId) {
        return ApiResponse.ok(service.report(userId));
    }
}
