package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConsultationRow {
    private Long id;
    private Long patientId;
    private String title;
    private String topic;
    private String symptoms;
    private String glucoseNote;
    private String medicationNote;
    private boolean anonymous;
    private String status;
    private Long doctorUserId;
    private String clientRequestId;
    private LocalDateTime createdAt;
    private String patientName;
}
