package com.threadfit.glucopilot.wallet.withdrawal.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.*;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalTransferService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/admin/v1/doctor-withdrawals")
@PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('doctor:withdrawal:manage')")
public class AdminWithdrawalController {
    private final WithdrawalService service;
    private final WithdrawalTransferService transfers;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<WithdrawalOrder>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Positive Long userId,
            @Valid PageQuery page) {
        return ok(service.list(userId, status, page));
    }

    @PostMapping("/{no}/review")
    public ResponseEntity<ApiResponse<WithdrawalOrder>> review(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String no,
            @Valid @RequestBody Review form) {
        return ok(service.review(RequestContext.requireOpsId(), no, form));
    }

    @PostMapping("/{no}/payout")
    public ResponseEntity<ApiResponse<WithdrawalOrder>> payout(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String no) {
        return ok(transfers.payout(no, RequestContext.requireOpsId()));
    }

    @PostMapping("/{no}/refresh")
    public ResponseEntity<ApiResponse<WithdrawalOrder>> refresh(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String no) {
        return ok(transfers.refresh(no, RequestContext.requireOpsId()));
    }

    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> calendar(
            @RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ok(service.calendar(start, end));
    }

    @PutMapping("/calendar")
    public ResponseEntity<ApiResponse<Void>> calendar(@Valid @RequestBody CalendarDay form) {
        service.calendar(RequestContext.requireOpsId(), form);
        return ok(null);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
