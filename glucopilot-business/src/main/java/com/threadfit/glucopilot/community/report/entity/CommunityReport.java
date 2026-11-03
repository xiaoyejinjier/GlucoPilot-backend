package com.threadfit.glucopilot.community.report.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_report; API response fields belong to dto. */
@Data
@TableName("community_report")
public class CommunityReport {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long reporterId;
    private String targetType;
    private Long targetId;
    private String reason;
    private String details;
    private String state;
    private String resolution;
    private String resolutionNote;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String clientRequestId;
    private LocalDateTime createdAt;
    private String targetTitle;
    private LocalDateTime feedbackReadAt;
}
