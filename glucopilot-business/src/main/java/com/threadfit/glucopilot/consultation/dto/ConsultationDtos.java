package com.threadfit.glucopilot.consultation.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

/** 问诊契约：医生审核、接诊、出单和沙箱钱包支付，不接受客户端决定收款方或金额。 */
public final class ConsultationDtos {
    private ConsultationDtos() {}

    public record Capabilities(
            String mode,
            boolean submissionEnabled,
            boolean doctorServiceEnabled,
            boolean paymentEnabled,
            int feeCents,
            String currency,
            String paymentProvider) {}

    public record PublishRequest(
            @NotBlank @Size(min = 4, max = 80) String title,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,15}") String topic,
            @NotBlank @Size(min = 10, max = 3000) String symptoms,
            @Size(max = 300) String glucoseNote,
            @Size(max = 1000) String medicationNote,
            @NotNull Boolean anonymous,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId,
            @Size(max = 6) List<@NotNull @Positive Long> imageMediaIds) {
        public PublishRequest(
                String title,
                String topic,
                String symptoms,
                String glucoseNote,
                String medicationNote,
                Boolean anonymous,
                String clientRequestId) {
            this(
                    title,
                    topic,
                    symptoms,
                    glucoseNote,
                    medicationNote,
                    anonymous,
                    clientRequestId,
                    List.of());
        }
    }

    /** 金额、收款医生、已支付状态均不接受客户端指定。 */
    public record OrderRequest(
            @NotNull @Positive Long reportId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId) {}

    public record PaymentRequest(
            @NotBlank @Pattern(regexp = "WALLET_SANDBOX") String provider,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId) {}

    public record DoctorApplication(
            @NotBlank @Size(max = 50) String realName,
            @NotBlank @Size(max = 120) String hospital,
            @NotBlank @Size(max = 80) String specialty,
            @NotBlank @Size(max = 80) String licenseNumber,
            @NotBlank @Size(max = 1000) String qualificationNote) {}

    public record DoctorReview(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED|DISABLED") String status,
            @NotBlank @Size(max = 500) String reviewNote,
            @NotNull @PositiveOrZero Long version) {}

    public record ReportRequest(
            @NotBlank @Size(max = 3000) String assessment,
            @NotBlank @Size(max = 2000) String medicationAdvice,
            @NotBlank @Size(max = 2000) String followUpAdvice,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId) {}

    public record ReportView(
            Long id,
            Long consultationId,
            boolean ready,
            String access,
            int feeCents,
            String doctorName,
            String specialty,
            String assessment,
            String medicationAdvice,
            String followUpAdvice,
            LocalDateTime createdAt) {}

    public record CaseView(
            Long id,
            String title,
            String topic,
            String status,
            String publicSummary,
            String patientName,
            boolean anonymous,
            LocalDateTime createdAt,
            String symptoms,
            String glucoseNote,
            String medicationNote,
            boolean mine,
            boolean detailsVisible,
            boolean canClaim,
            boolean canReply,
            ReportView report,
            String topicName) {}
}
