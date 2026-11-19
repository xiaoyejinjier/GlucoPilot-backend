package com.threadfit.glucopilot.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class SandboxPaymentDtos {
    private SandboxPaymentDtos() {}

    public record Payment(
            String orderNo,
            String status,
            int amountCents,
            String qrCode,
            String pngBase64,
            LocalDateTime expiresAt,
            String paymentMode,
            String pageUrl) {
        public Payment(
                String orderNo,
                String status,
                int amountCents,
                String qrCode,
                String pngBase64,
                LocalDateTime expiresAt) {
            this(orderNo, status, amountCents, qrCode, pngBase64, expiresAt, "QR", null);
        }
    }

    /** 只由经过SDK验签的支付宝响应/通知生成，不接受前端传入此结构。 */
    public record Proof(
            String orderNo,
            String tradeNo,
            String status,
            BigDecimal amount,
            String appId,
            String sellerId) {}
}
