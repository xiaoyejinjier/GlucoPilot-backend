package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

@Data
public class ConsultationOrder {
    private String orderNo;
    private Long userId;
    private Long consultationId;
    private Long reportId;
    private Long doctorUserId;
    private int amountCents;
    private String status;
    private String paymentProvider;
    private String clientRequestId;
}
