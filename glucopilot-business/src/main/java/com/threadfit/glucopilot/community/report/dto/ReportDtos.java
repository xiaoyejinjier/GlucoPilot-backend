package com.threadfit.glucopilot.community.report.dto;

import jakarta.validation.constraints.*;

public final class ReportDtos {
    private ReportDtos() {}

    public record ReportRequest(
            @Pattern(regexp = "ARTICLE|COMMENT|USER|MESSAGE") @NotBlank String targetType,
            @Positive long targetId,
            @Pattern(regexp = "MISINFORMATION|SPAM|ABUSE|PRIVACY|OTHER") @NotBlank String reason,
            @Size(max = 500) String details,
            @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") @NotBlank String clientRequestId) {}

    public record Resolution(@NotBlank String action, @NotBlank @Size(max = 1000) String note) {}
}
