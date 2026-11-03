package com.threadfit.glucopilot.community.tag.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

/** Persistent fields of community_tag; API response fields belong to dto. */
@Data
@TableName("community_tag")
public class CommunityTag {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private Integer sortNo;
    private Integer enabled;
}
