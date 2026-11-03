package com.threadfit.glucopilot.community.report.controller;

import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.community.report.service.CommunityReportService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/v1/community")
@RequiredArgsConstructor
public class AdminCommunityReportController {
    private final CommunityReportService service;

    @GetMapping("/reports")
    @PreAuthorize("@ss.hasPermi('content:news:list')")
    public ApiResponse<PageResult<Map<String, Object>>> reports(
            @RequestParam(required = false) String status, @Valid PageQuery q) {
        return ApiResponse.ok(service.reports(status, q));
    }

    @PostMapping("/reports/{id}/resolve")
    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('content:news:edit')")
    public ApiResponse<Void> resolve(
            @PathVariable long id, @Valid @RequestBody Resolution request) {
        service.resolveReport(RequestContext.requireOpsId(), id, request);
        return ApiResponse.ok();
    }
}
