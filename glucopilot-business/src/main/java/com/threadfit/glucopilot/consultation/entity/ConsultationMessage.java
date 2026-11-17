package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConsultationMessage {
    private Long id;
    private Long consultationId;
    private Long senderId;
    private String senderRole;
    private String content;
    private String clientRequestId;
    private LocalDateTime createdAt;
    private boolean mine;
}
