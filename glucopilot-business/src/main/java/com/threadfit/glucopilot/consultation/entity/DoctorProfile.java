package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DoctorProfile {
    private Long userId;
    private String realName;
    private String hospital;
    private String specialty;
    private String licenseNumber;
    private String qualificationNote;
    private String status;
    private String reviewNote;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private long version;
    private LocalDateTime createdAt;
}
