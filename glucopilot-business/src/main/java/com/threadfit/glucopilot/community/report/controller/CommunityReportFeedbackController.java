package com.threadfit.glucopilot.community.report.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.report.dto.CommunityReportFeedbackDtos;
import com.threadfit.glucopilot.community.report.service.CommunityReportFeedbackService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community/reports")
public class CommunityReportFeedbackController {
    private final CommunityReportFeedbackService service;

    @GetMapping("/mine")
    public ApiResponse<PageResult<CommunityReportFeedbackDtos.Feedback>> mine(@Valid PageQuery q) {
        return ApiResponse.ok(service.mine(RequestContext.requireUserId(), q));
    }

    @GetMapping("/{id}")
    public ApiResponse<CommunityReportFeedbackDtos.Feedback> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(RequestContext.requireUserId(), id));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable long id) {
        service.read(RequestContext.requireUserId(), id);
        return ApiResponse.ok();
    }
}
