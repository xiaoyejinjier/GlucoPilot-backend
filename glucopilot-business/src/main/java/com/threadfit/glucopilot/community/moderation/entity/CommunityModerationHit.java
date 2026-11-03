package com.threadfit.glucopilot.community.moderation.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_moderation_hit; API response fields belong to dto. */
@Data
@TableName("community_moderation_hit")
public class CommunityModerationHit {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String targetType;
    private Long targetId;
    private Integer revision;
    private String hitSource;
    private String categoryCode;
    private Integer severity;
    private Integer confidence;
    private String reason;
    private String modelCode;
    private String promptVer;
    private Integer startOffset;
    private Integer endOffset;
    private String snippetHash;
    private Integer snippetLen;
    private LocalDateTime createdAt;
}
