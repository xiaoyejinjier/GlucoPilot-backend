package com.threadfit.glucopilot.notification.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 待办数量和最新创建时间的类型化查询结果。 */
@Data
public class WorkReminderCount {
    private Long total;
    private LocalDateTime latestAt;
}
