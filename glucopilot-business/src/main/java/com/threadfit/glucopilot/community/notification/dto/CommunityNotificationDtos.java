package com.threadfit.glucopilot.community.notification.dto;

import com.threadfit.glucopilot.community.profile.dto.ProfileDtos;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

/** Community API request/response models. */
public final class CommunityNotificationDtos {
    private CommunityNotificationDtos() {}

    public record Notice(
            String id,
            String kind,
            ProfileDtos.Profile actor,
            String newsId,
            String newsTitle,
            String commentId,
            String content,
            LocalDateTime createdAt,
            boolean read) {}

    public record Summary(
            long messages,
            long likes,
            long collections,
            long comments,
            long follows,
            long reports,
            long total,
            String latestMessageId,
            String latestSenderId,
            String latestNotificationId,
            String latestReportId) {}

    public record Read(String kind, String throughId) {}
}
