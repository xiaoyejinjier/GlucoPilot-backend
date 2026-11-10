package com.threadfit.glucopilot.notification.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 仅供本人提醒投影，不读取管理员内部备注。 */
@Data
public class UserReminderRow {
    private Long sourceId;
    private Long recordId;
    private LocalDate recordDate;
    private String nodeCode;
    private BigDecimal glucoseValue;
    private LocalDateTime recordedAt;
    private String userReply;
    private LocalDateTime repliedAt;
    private LocalDateTime readAt;
}
