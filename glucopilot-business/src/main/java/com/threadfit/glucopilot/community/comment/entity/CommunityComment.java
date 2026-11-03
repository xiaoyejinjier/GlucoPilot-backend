package com.threadfit.glucopilot.community.comment.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_comment; API response fields belong to dto. */
@Data
@TableName("community_comment")
public class CommunityComment {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long newsId;
    private Long userId;
    private String content;
    private String clientRequestId;
    private LocalDateTime createdAt;
    @TableLogic private Integer isDeleted;
    private Long replyToCommentId;
    private Long rootCommentId;
    private String aiStatus;
    private String aiVerdict;
    private String aiReason;
    private String aiModel;
    private String aiCategory;
    private Integer aiSeverity;
    private Integer aiConfidence;
    private String aiPromptVer;
    private LocalDateTime aiClaimedAt;
    private LocalDateTime aiCheckedAt;
    private Integer isHidden;
}
