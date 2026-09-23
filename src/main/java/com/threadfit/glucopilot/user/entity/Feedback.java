package com.threadfit.glucopilot.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 用户反馈与官方回复。C 端只写入与读取，officialReply 的写入属于运营端。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("feedback")
public class Feedback extends BaseEntity {

    private Long userId;
    /** 1建议2Bug3诉求4其他 */
    private Integer feedbackType;
    private String contentText;
    /** 0已收到1处理中2已处理3已关闭 */
    private Integer replyStatus;
    private String officialReply;
    private Long repliedBy;
    private LocalDateTime repliedAt;
}
