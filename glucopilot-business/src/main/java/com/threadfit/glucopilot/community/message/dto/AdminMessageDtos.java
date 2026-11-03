package com.threadfit.glucopilot.community.message.dto;

import java.time.LocalDateTime;

public final class AdminMessageDtos {
    private AdminMessageDtos() {}

    public record MessageItem(
            String id,
            String senderId,
            String senderNickname,
            String recipientId,
            String recipientNickname,
            String content,
            LocalDateTime createdAt,
            LocalDateTime readAt) {}
}
