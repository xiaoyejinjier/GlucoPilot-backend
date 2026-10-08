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
    /**
     * 图片内容摘要（多模态阶段 1 的产物，见 V9 迁移）。
     * <p>
     * 纯图片消息的 {@code contentText} 为空，而记忆回放会丢弃文本为空的行，
     * 因此必须单独有一列承载「这张图大概是什么」，否则图片永远进不了会话记忆。
     * 与语音的 {@link #asrText} 语义对称，不可复用 {@code contentText}（那是用户原话）。
     */
    private String mediaCaption;
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
