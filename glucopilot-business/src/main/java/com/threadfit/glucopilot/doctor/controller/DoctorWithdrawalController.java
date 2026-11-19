package com.threadfit.glucopilot.doctor.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.doctor.service.DoctorPortalService;
import com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.*;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalTransferService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/v1/doctor/withdrawals")
@PreAuthorize("@ss.hasRole('doctor') and @ss.hasPermi('doctor:income:view')")
public class DoctorWithdrawalController {
    private final DoctorPortalService doctors;
    private final WithdrawalService service;
    private final WithdrawalTransferService transfers;

    private long viewer() {
        return doctors.doctorId(RequestContext.requireOpsId());
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Summary>> summary() {
        return ok(service.summary(viewer()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<WithdrawalOrder>>> list(
            @RequestParam(required = false) String status, @Valid PageQuery page) {
        return ok(service.list(viewer(), status, page));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WithdrawalOrder>> apply(@Valid @RequestBody Apply form) {
        return ok(service.apply(viewer(), form));
    }

    @PostMapping("/{no}/recipient")
    public ResponseEntity<ApiResponse<WithdrawalOrder>> recipient(@PathVariable String no,@Valid @RequestBody Recipient form) {
        return ok(service.recipient(viewer(),no,form));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh() {
        transfers.refreshOwn(viewer());
        return ok(null);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
