package com.threadfit.glucopilot.monitor;

import com.threadfit.glucopilot.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/ruoyi/monitor")
@RequiredArgsConstructor
public class ResourceMonitorController {
    private final ResourceMetricsService metrics;

    @GetMapping("/resources")
    @PreAuthorize("@ss.hasPermi('monitor:server:list')")
    public ApiResponse<ResourceMetrics.Response> resources() {
        return ApiResponse.ok(metrics.read());
    }
}
