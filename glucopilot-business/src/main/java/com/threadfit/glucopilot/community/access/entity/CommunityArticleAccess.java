package com.threadfit.glucopilot.community.access.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_article_access; API response fields belong to dto. */
@Data
@TableName("community_article_access")
public class CommunityArticleAccess {
    @TableId(value = "news_id", type = IdType.INPUT)
    private Long newsId;

    private String visibility;
    private Integer revision;
    private LocalDateTime updatedAt;
}
