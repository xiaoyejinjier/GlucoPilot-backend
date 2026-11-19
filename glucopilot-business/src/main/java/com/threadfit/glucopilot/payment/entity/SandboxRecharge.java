package com.threadfit.glucopilot.payment.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SandboxRecharge {
    private String orderNo;
    private long userId;
    private int amountCents;
    private String status;
    private String paymentProvider;
    private String paymentMode;
    private String clientRequestId;
    private String providerTradeNo;
    private String sandboxAppId;
    private String sandboxSellerId;
    private String qrCode;
    private LocalDateTime expiresAt;
}
