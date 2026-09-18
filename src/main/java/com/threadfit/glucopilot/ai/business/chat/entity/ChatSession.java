package com.threadfit.glucopilot.ai.business.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 聊天会话（对应 chat_session）。
 * 列名与驼峰一一对应（client_msg_id 这类由 map-underscore-to-camel-case 处理），
 * 无「数字粘连」列，故不需要额外的 @TableField。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    private Long userId;
    private String title;
    /** 0 归档 1 进行中 2 已结束 */
    private Integer sessionStatus;
    /** 1 纯文字 2 纯语音 3 混合 */
    private Integer sessionType;
    private String modelCode;
    private String systemPromptVer;
    private String contextSummary;
    private Long summaryUptoMsgId;
    /** 送入模型的最近原文条数 */
    private Integer windowMsgCount;
    /** 消息条数冗余 */
    private Integer msgCount;
    private LocalDateTime lastMsgAt;
    /** 0 否 1 是 */
    private Integer isPinned;
}
