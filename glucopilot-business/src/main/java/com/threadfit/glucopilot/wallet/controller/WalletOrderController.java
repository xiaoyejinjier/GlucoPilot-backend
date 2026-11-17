package com.threadfit.glucopilot.wallet.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class WalletOrderController {
    private final WalletOrderService service;

    @GetMapping("/wallet")
    public ResponseEntity<ApiResponse<WalletDtos.Summary>> summary() {
        return noStore(service.summary(RequestContext.requireUserId()));
    }

    @PostMapping("/wallet/activation")
    public ResponseEntity<ApiResponse<WalletDtos.Summary>> activate() {
        return noStore(service.activate(RequestContext.requireUserId()));
    }

    @GetMapping("/wallet/recharge-orders")
    public ResponseEntity<ApiResponse<PageResult<WalletDtos.Order>>> recharges(
            @Valid WalletDtos.OrderQuery query) {
        return noStore(service.list(RequestContext.requireUserId(), true, query));
    }

    @GetMapping("/consultation-orders")
    public ResponseEntity<ApiResponse<PageResult<WalletDtos.Order>>> consultations(
            @Valid WalletDtos.OrderQuery query) {
        return noStore(service.list(RequestContext.requireUserId(), false, query));
    }

    @GetMapping("/wallet/recharge-orders/{orderNo}")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> rechargeDetail(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo) {
        return noStore(service.detail(RequestContext.requireUserId(), true, orderNo));
    }

    @GetMapping("/consultation-orders/{orderNo}")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> consultationDetail(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo) {
        return noStore(service.detail(RequestContext.requireUserId(), false, orderNo));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
