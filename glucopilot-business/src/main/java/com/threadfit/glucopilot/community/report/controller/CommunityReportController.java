package com.threadfit.glucopilot.community.report.controller;

import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.report.service.CommunityReportService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/community")
@RequiredArgsConstructor
public class CommunityReportController {
    private final CommunityReportService service;

    @PostMapping("/reports")
    public ApiResponse<Map<String, Object>> report(@Valid @RequestBody ReportRequest request) {
        return ApiResponse.ok(service.report(RequestContext.requireUserId(), request));
    }
}
