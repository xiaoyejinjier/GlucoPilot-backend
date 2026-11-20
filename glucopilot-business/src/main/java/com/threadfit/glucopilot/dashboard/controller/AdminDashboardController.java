package com.threadfit.glucopilot.dashboard.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.constant.PermCodes;
import com.threadfit.glucopilot.dashboard.dto.DashboardDtos;
import com.threadfit.glucopilot.dashboard.service.AdminDashboardService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminDashboardService service;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('" + PermCodes.DASHBOARD_VIEW + "')")
    public ResponseEntity<ApiResponse<DashboardDtos.Stats>> summary() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.summary()));
    }
}
