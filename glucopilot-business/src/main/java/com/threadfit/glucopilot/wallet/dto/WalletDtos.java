package com.threadfit.glucopilot.wallet.dto;

import com.threadfit.glucopilot.common.api.PageQuery;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

public final class WalletDtos {
    private WalletDtos() {}

    /** 未建立余额账本前返回null，不能把订单合计伪装成可用余额。 */
    public record Summary(
            Long balanceCents,
            String currency,
            boolean ledgerReady,
            boolean rechargeEnabled,
            boolean paymentEnabled,
            int consultationFeeCents,
            boolean available,
            boolean activated,
            LocalDateTime activatedAt,
            boolean sandbox) {}

    @Getter
    @Setter
    public static class OrderQuery extends PageQuery {
        @Pattern(regexp = "PENDING|PAID|CLOSED|REFUNDING|REFUNDED")
        private String status;
    }

    public record RechargeRequest(
            @NotNull @Min(100) @Max(100000) Integer amountCents,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId,
            @Pattern(regexp = "QR|PAGE") String paymentMode) {
        public RechargeRequest(Integer amountCents, String clientRequestId) {
            this(amountCents, clientRequestId, "QR");
        }

        public RechargeRequest {
            if (paymentMode == null) paymentMode = "QR";
        }
    }

    public record Order(
            String orderNo,
            String type,
            String title,
            String status,
            int amountCents,
            String currency,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            LocalDateTime closedAt,
            LocalDateTime creditedAt,
            Long consultationId,
            Long reportId,
            Long doctorUserId,
            String settlementStatus) {}
}
