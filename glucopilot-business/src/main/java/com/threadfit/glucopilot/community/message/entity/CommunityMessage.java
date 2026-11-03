package com.threadfit.glucopilot.community.message.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_message; API response fields belong to dto. */
@Data
@TableName("community_message")
public class CommunityMessage {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long senderId;
    private Long recipientId;
    private String content;
    private String clientRequestId;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
