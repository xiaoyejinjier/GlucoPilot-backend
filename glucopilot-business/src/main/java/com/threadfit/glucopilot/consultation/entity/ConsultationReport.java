package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConsultationReport {
    private Long id;
    private Long consultationId;
    private Long doctorUserId;
    private String doctorName;
    private String specialty;
    private String assessment;
    private String medicationAdvice;
    private String followUpAdvice;
    private String clientRequestId;
    private LocalDateTime createdAt;
}
