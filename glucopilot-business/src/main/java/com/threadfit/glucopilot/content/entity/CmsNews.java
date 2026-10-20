package com.threadfit.glucopilot.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_news")
public class CmsNews extends BaseEntity {

    private String title;
    private String summaryText;
    private Integer channelCode;
    private Integer contentType;
    private String coverUrl;
    private String videoUrl;
    private String contentBody;
    private LocalDateTime publishAt;
    private Integer publishStatus;
    private Integer likeCount;
    private Integer collectCount;
    private Long bloggerId;
    private Integer communityManaged;
}
