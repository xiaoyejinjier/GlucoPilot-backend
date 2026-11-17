package com.threadfit.glucopilot.notification.controller;

import com.threadfit.glucopilot.auth.service.RuoYiAuthorizationService;
import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.notification.service.AdminWorkReminderService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AdminWorkReminderController {
    private final AdminWorkReminderService service;
    private final RuoYiAuthorizationService authorization;

    @GetMapping("/admin/v1/work-reminders")
    @PreAuthorize(
            "@ss.hasAnyPermi('content:news:list,feedback:manage,food:library:manage,health:alert:view')")
    public ResponseEntity<ApiResponse<AdminWorkReminderService.Summary>> summary() {
        Long opsId = RequestContext.getOpsId();
        if (opsId == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.ok(
                                service.summary(opsId, authorization.permissionCodesOf(opsId))));
    }

    @PutMapping("/admin/v1/work-reminders/read")
    @PreAuthorize(
            "@ss.hasAnyPermi('content:news:list,feedback:manage,food:library:manage,health:alert:view')")
    public ApiResponse<Void> read(
            @Valid @RequestBody AdminWorkReminderService.ReadRequest request) {
        Long opsId = RequestContext.getOpsId();
        if (opsId == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        service.read(opsId, authorization.permissionCodesOf(opsId), request);
        return ApiResponse.ok(null);
    }
}
