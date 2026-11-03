package com.threadfit.glucopilot.community.draft.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_article_draft; API response fields belong to dto. */
@Data
@TableName("community_article_draft")
public class CommunityArticleDraft {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String body;
    private String tagCodes;
    private String customTags;
    private String imageIds;
    private String videoIds;
    private String visibility;
    private String viewerIds;
    private Integer revision;
    private Long sourceArticleId;
    private Integer sourceRevision;
    private String clientRequestId;
    private String lastRequestId;
    private Long publishedNewsId;
    private String publishRequestId;
    @TableLogic private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
