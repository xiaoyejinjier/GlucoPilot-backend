package com.threadfit.glucopilot.community.report.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

/** Community API request/response models. */
public final class CommunityReportFeedbackDtos {
    private CommunityReportFeedbackDtos() {}

    public record Feedback(
            String id,
            String targetType,
            String targetId,
            String targetTitle,
            String reason,
            String details,
            String state,
            String resolution,
            String resolutionNote,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt,
            boolean feedbackRead) {}
}
