package com.threadfit.glucopilot.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_banner")
public class CmsBanner extends BaseEntity {

    private String title;
    private String subtitle;
    private String imageUrl;
    private String linkUrl;
    private Integer sortNo;
    private Integer shelfStatus;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
}
