package com.threadfit.glucopilot.wallet.withdrawal.entity;

import lombok.Data;

import java.time.*;

@Data
public class WithdrawalOrder {
    private String orderNo;
    private Long userId;
    private String doctorName;
    private Integer amountCents;
    private String status;
    private String provider;
    private String clientRequestId;
    private String reviewNote;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDate expectedPayoutDate;
    private String payoutReference;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private Long version;
}
