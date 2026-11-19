package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConsultationConversation {
    private Long consultationId;
    private String title;
    private String peerName;
    private String peerRole;
    private String lastContent;
    private Long lastMessageId;
    private LocalDateTime lastMessageAt;
    private long unreadCount;
}
