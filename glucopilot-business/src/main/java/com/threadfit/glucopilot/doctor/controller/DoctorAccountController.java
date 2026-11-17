package com.threadfit.glucopilot.doctor.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.doctor.dto.DoctorPortalDtos.*;
import com.threadfit.glucopilot.doctor.service.DoctorPortalService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/admin/v1")
@PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('doctor:account:manage')")
public class DoctorAccountController {
    private final DoctorPortalService doctors;

    @GetMapping("/doctor-accounts")
    public ResponseEntity<ApiResponse<PageResult<Account>>> list(@Valid PageQuery page) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(doctors.accounts(page)));
    }

    @PostMapping("/doctor-accounts/{userId}")
    public ApiResponse<Void> create(
            @PathVariable @Positive long userId, @Valid @RequestBody CreateAccount form) {
        doctors.create(RequestContext.requireOpsId(), userId, form);
        return ApiResponse.ok();
    }

    @PutMapping("/doctor-accounts/{userId}")
    public ApiResponse<Void> update(
            @PathVariable @Positive long userId, @Valid @RequestBody UpdateAccount form) {
        doctors.update(userId, form);
        return ApiResponse.ok();
    }

    @GetMapping("/doctor-role-permissions")
    public ResponseEntity<ApiResponse<Permissions>> permissions() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(doctors.permissions()));
    }

    @PutMapping("/doctor-role-permissions")
    public ApiResponse<Permissions> permissions(@Valid @RequestBody Permissions form) {
        return ApiResponse.ok(doctors.permissions(form));
    }
}
