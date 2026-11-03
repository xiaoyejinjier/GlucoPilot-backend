package com.threadfit.glucopilot.community.follow.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.LocalDateTime;

/** Persistent fields of community_follow; API response fields belong to dto. */
@Data
@TableName("community_follow")
public class CommunityFollow {
    private Long followerId;
    private String targetKey;
    private Integer following;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
