package com.threadfit.glucopilot.wallet.withdrawal.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

import java.time.*;

@Data
public class WithdrawalOrder {
    private String orderNo;
    private Long userId;
    private String doctorName;
    /** 申请人账号状态（管理端列表联表带出，标识注销中/已注销）。 */
    private Integer accountStatus;
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
    private String payeeUserId;
    @JsonIgnore private String sandboxAppId;
    @JsonIgnore private String sandboxSellerId;
    private String transferState;
    private String transferErrorCode;
    private LocalDateTime transferStartedAt;
    private LocalDateTime lastTransferAt;
    @JsonIgnore private LocalDateTime transferLeaseUntil;
}
