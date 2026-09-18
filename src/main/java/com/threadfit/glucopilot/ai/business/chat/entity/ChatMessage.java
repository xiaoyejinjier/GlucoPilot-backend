package com.threadfit.glucopilot.ai.business.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.threadfit.glucopilot.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天消息（对应 chat_message，只追加）。
 * 助手消息的 token / 耗时 / 结束原因都落在这里，用于后台成本统计与排障。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    private Long sessionId;
    private Long userId;
    /** 会话内顺序号 */
    private Integer seqNo;
    /** 1 用户 2 助手 3 系统 */
    private Integer msgRole;
    /** 1 文本 2 图片 3 语音 */
    private Integer msgType;
    private String contentText;
    private Long mediaId;
    private Long voiceMediaId;
    private String asrText;
    private Integer voiceDurationMs;
    /** 被回复的用户消息 ID */
    private Long replyToMsgId;
    private String modelCode;
    private Integer promptTokens;
    private Integer completionTokens;
    /** 1 正常 2 长度截断 3 内容过滤 4 错误 */
    private Integer finishReason;
    private Integer latencyMs;
    /** 客户端幂等 ID；唯一键 (session_id, client_msg_id, is_deleted) */
    private String clientMsgId;
}
