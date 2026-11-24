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

/** 充值支付的HTTP入口：普通用户接口校验登录身份，支付宝回调由支付服务验签确认。 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AlipaySandboxController {
    private final SandboxRechargeService orders;
    private final SandboxPaymentService payments;
    private final WalletOrderService wallets;

    // 【1. 创建订单】校验请求金额/编号，用户身份只从登录会话取得，返回本人创建的订单详情。
    @PostMapping("/wallet/recharge-orders")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> create(
            @Valid @RequestBody WalletDtos.RechargeRequest request) {
        long userId = RequestContext.requireUserId();
        var order = orders.create(userId, request);
        return ok(wallets.detail(userId, true, order.getOrderNo()));
    }

    // 【2. 获取付款信息】返回签名收银台链接或兼容旧扫码入口；本接口不会直接增加钱包余额。
    @PostMapping("/wallet/recharge-orders/{orderNo}/payments")
    public ResponseEntity<ApiResponse<Payment>> pay(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo) {
        return ok(payments.pay(RequestContext.requireUserId(), orderNo));
    }

    // 【3. 主动查单】先请求支付宝核实结果，再读取订单最新状态和已有的交易后余额。
    @PostMapping("/wallet/recharge-orders/{orderNo}/refresh")
    public ResponseEntity<ApiResponse<WalletDtos.Order>> refresh(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderNo) {
        long userId = RequestContext.requireUserId();
        payments.refresh(userId, orderNo);
        return ok(wallets.detail(userId, true, orderNo));
    }

    /**
     * 【4. 支付宝异步通知】接收表单，由payments.notify先验签、再对账入账，不依赖用户登录。
     * 支付宝协议要求纯文本success/failure；不能包装成普通业务接口的JSON响应。
     */
    @PostMapping(
            value = "/payments/alipay/sandbox/notify",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notify(@RequestParam MultiValueMap<String, String> source) {
        var flat = new HashMap<String, String>();
        for (var entry : source.entrySet()) {
            // 同一参数出现多个值时直接拒绝，避免验签与业务读取采用不同值。
            if (entry.getValue().size() != 1) return callback(false);
            flat.put(entry.getKey(), entry.getValue().getFirst());
        }
        return callback(payments.notify(flat));
    }

    // 验签和业务处理正常返回success；处理失败返回failure，允许支付宝后续重试通知。
    private ResponseEntity<String> callback(boolean success) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(success ? "success" : "failure");
    }

    // 钱包及订单状态使用统一业务响应，并禁止缓存，避免页面重复展示旧支付状态。
    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
