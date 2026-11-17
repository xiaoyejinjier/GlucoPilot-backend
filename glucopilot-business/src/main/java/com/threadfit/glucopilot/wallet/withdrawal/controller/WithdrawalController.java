package com.threadfit.glucopilot.wallet.withdrawal.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.*;
import com.threadfit.glucopilot.wallet.withdrawal.entity.WithdrawalOrder;
import com.threadfit.glucopilot.wallet.withdrawal.service.WithdrawalService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet/withdrawals")
public class WithdrawalController {
    private final WithdrawalService service;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Summary>> summary() {
        return ok(service.summary(RequestContext.requireUserId()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<WithdrawalOrder>>> list(
            @RequestParam(required = false) String status, @Valid PageQuery page) {
        return ok(service.list(RequestContext.requireUserId(), status, page));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WithdrawalOrder>> apply(@Valid @RequestBody Apply form) {
        return ok(service.apply(RequestContext.requireUserId(), form));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
