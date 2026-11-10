package com.threadfit.glucopilot.notification.dto;

import com.threadfit.glucopilot.common.api.PageResult;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class UserReminderDtos {
    private UserReminderDtos() {}

    public record Item(
            String kind,
            String id,
            String revision,
            String recordId,
            LocalDate recordDate,
            String nodeCode,
            String nodeName,
            BigDecimal glucoseValue,
            LocalDateTime recordedAt,
            String userReply,
            LocalDateTime repliedAt,
            boolean read) {}

    public record Result(LocalDate date, List<Item> alerts, PageResult<Item> replies) {}

    public record Read(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String revision) {}
}
