package com.threadfit.glucopilot.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("news_user_action")
public class NewsUserAction extends BaseEntity {

    private Long userId;
    private Long newsId;
    private Integer isLiked;
    private Integer isCollected;
}
