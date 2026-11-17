package com.threadfit.glucopilot.consultation.dto;

import com.threadfit.glucopilot.consultation.entity.ConsultationMessage;

import jakarta.validation.constraints.*;

import java.util.List;

public final class ConsultationConversationDtos {
    private ConsultationConversationDtos() {}

    public record Photo(Long mediaId, String url) {}

    public record Send(
            @NotBlank @Size(max = 2000) String content,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId) {}

    public record Messages(
            List<ConsultationMessage> records,
            boolean hasMore,
            Long nextBeforeId,
            Long nextAfterId,
            boolean canSend) {}
}
