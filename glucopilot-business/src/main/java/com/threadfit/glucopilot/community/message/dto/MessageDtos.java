package com.threadfit.glucopilot.community.message.dto;

import com.threadfit.glucopilot.community.profile.dto.ProfileDtos.Profile;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

public final class MessageDtos {
    private MessageDtos() {}

    public record MessageRequest(
            @NotBlank @Size(max = 1000) String content,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientRequestId) {}

    public record ReadRequest(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String throughId) {}

    public record Message(
            String id,
            String senderId,
            String recipientId,
            String content,
            LocalDateTime createdAt,
            LocalDateTime readAt,
            boolean mine) {}

    public record MessagePermission(
            boolean canSend, boolean following, boolean mutualFollowing, String state) {}

    public record Messages(
            List<Message> records,
            boolean hasMore,
            String nextBeforeId,
            MessagePermission sendPermission) {}

    public record Conversation(Profile peer, Message lastMessage, long unreadCount) {}

    public record Unread(long unreadCount, String latestMessageId, String latestSenderId) {}
}
