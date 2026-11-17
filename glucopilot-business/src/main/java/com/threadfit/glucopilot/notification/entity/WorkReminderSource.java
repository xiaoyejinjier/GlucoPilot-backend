package com.threadfit.glucopilot.notification.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 管理端待办来源快照；只在服务端用于计算已读，不返回业务私密内容。 */
@Data
public class WorkReminderSource {
    private Long id;
    private String revision;
    private LocalDateTime latestAt;
    private LocalDateTime readAt;
}
