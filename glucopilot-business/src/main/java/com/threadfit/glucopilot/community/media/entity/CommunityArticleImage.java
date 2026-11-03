package com.threadfit.glucopilot.community.media.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

/** Persistent fields of community_article_image; API response fields belong to dto. */
@Data
@TableName("community_article_image")
public class CommunityArticleImage {
    private Long newsId;
    private Long mediaId;
    private Integer sortNo;
}
