package com.threadfit.glucopilot.wallet.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 两类订单共用的只读投影；不读取渠道凭证或支付密钥。 */
@Data
public class WalletOrderRow {
    private String orderNo;
    private String status;
    private int amountCents;
    private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
    private LocalDateTime creditedAt;
    private Long consultationId;
    private Long reportId;
    private Long doctorUserId;
    private String settlementStatus;
}
