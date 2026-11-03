package com.threadfit.glucopilot.community.notification.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_notification; API response fields belong to dto. */
@Data
@TableName("community_notification")
public class CommunityNotification {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long recipientId;
    private Long actorId;
    private String kind;
    private Long targetId;
    private Long newsId;
    private Long commentId;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
