package com.threadfit.glucopilot.consultation.entity;

import lombok.Data;

/** 同一问诊仅一条提醒，不在全局汇总中暴露健康交流正文。 */
@Data
public class ConsultationUnreadNotice {
    private Long consultationId;
    private String title;
    private String peerName;
    private Long lastMessageId;
    private long unreadCount;
}
