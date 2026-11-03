package com.threadfit.glucopilot.community.article.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_article; API response fields belong to dto. */
@Data
@TableName("community_article")
public class CommunityArticle {
    @TableId(value = "news_id", type = IdType.INPUT)
    private Long newsId;

    private Long authorUserId;
    private String clientRequestId;
    private String seedKey;
    private String state;
    private Integer revision;
    private String aiVerdict;
    private String aiReason;
    private String aiModel;
    private String aiCategory;
    private Integer aiSeverity;
    private Integer aiConfidence;
    private String aiPromptVer;
    private LocalDateTime aiCheckedAt;
    private String reviewNote;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
