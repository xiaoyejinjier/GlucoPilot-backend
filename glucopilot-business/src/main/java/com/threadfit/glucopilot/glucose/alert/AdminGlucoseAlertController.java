package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/admin/v1/glucose-alerts")
@RequiredArgsConstructor
@Validated
public class AdminGlucoseAlertController {
    private final GlucoseAlertService service;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('health:alert:view')")
    public ApiResponse<GlucoseAlertDtos.Result> page(
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(required=false) String keyword,
        @RequestParam(required=false) String alertType,
        @RequestParam(required=false) String status,@Valid PageQuery page) {
        return ApiResponse.ok(service.page(startDate,endDate,keyword,alertType,status,page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('health:alert:view')")
    public ApiResponse<GlucoseAlertDtos.Detail> detail(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PutMapping("/{id}/handling")
    @PreAuthorize("@ss.hasPermi('health:alert:handle')")
    public ApiResponse<GlucoseAlertDtos.Event> handle(@PathVariable @Positive long id,@Valid @RequestBody GlucoseAlertDtos.Handling request) {
        return ApiResponse.ok(service.handle(RequestContext.requireOpsId(),id,request));
    }
}
