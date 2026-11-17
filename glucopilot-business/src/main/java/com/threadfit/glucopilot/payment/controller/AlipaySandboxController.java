package com.threadfit.glucopilot.payment.controller;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.payment.dto.SandboxPaymentDtos.Payment;
import com.threadfit.glucopilot.payment.service.*;
import com.threadfit.glucopilot.wallet.dto.WalletDtos;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AlipaySandboxController {
    private final SandboxRechargeService orders;
    private final SandboxPaymentService payments;
    private final WalletOrderService wallets;

    @PostMapping("/wallet/recharge-orders")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> create(
            @Valid @RequestBody WalletDtos.RechargeRequest request) {
        long userId = RequestContext.requireUserId();
        var order = orders.create(userId, request);
        return ok(wallets.detail(userId, true, order.getOrderNo()));
    }

    @PostMapping("/wallet/recharge-orders/{orderNo}/payments")
    public ResponseEntity<ApiResponse<Payment>> pay(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo) {
        return ok(payments.pay(RequestContext.requireUserId(), orderNo));
    }

    @PostMapping("/wallet/recharge-orders/{orderNo}/refresh")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> refresh(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo) {
        long userId = RequestContext.requireUserId();
        payments.refresh(userId, orderNo);
        return ok(wallets.detail(userId, true, orderNo));
    }

    /** 支付宝协议要求纯文本success/failure；不能包装成JSON。 */
    @PostMapping(
            value = "/payments/alipay/sandbox/notify",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notify(@RequestParam MultiValueMap<String, String> source) {
        var flat = new HashMap<String, String>();
        for (var entry : source.entrySet()) {
            if (entry.getValue().size() != 1) return callback(false);
            flat.put(entry.getKey(), entry.getValue().getFirst());
        }
        return callback(payments.notify(flat));
    }

    private ResponseEntity<String> callback(boolean success) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(success ? "success" : "failure");
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
