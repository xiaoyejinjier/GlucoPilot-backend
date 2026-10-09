package com.threadfit.glucopilot.ai.business.chat.dto;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 对话接口出入参，对应《接口设计文档》2.23 / 2.24 / 2.25。
 * Long 由全局 Jackson 配置序列化为字符串（防前端精度丢失），无需逐字段标注。
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** 2.23 获取或创建当前会话 */
    @Data
    public static class CurrentSessionRequest {
        /** 1 纯文字 2 纯语音 3 混合；缺省 3 */
        @Min(1)
        @Max(3)
        private Integer sessionType;
    }

    @Data
    @Builder
    public static class SessionResponse {
        private Long id;
        private Integer sessionStatus;
        private String title;
        private Integer sessionType;
        private Integer msgCount;

        public static SessionResponse from(ChatSession session) {
            return SessionResponse.builder()
                    .id(session.getId())
                    .sessionStatus(session.getSessionStatus())
                    .title(session.getTitle())
                    .sessionType(session.getSessionType())
                    .msgCount(session.getMsgCount())
                    .build();
        }
    }

    /** 2.24 发送聊天消息 */
    @Data
    public static class SendMessageRequest {
        @NotBlank(message = "clientMsgId 不能为空")
        @Size(max = 64, message = "clientMsgId 长度不能超过 64")
        private String clientMsgId;

        /** 1 文本 2 图片 3 语音 */
        @NotNull(message = "msgType 不能为空")
        @Min(value = 1, message = "msgType 仅支持 1/2/3")
        @Max(value = 3, message = "msgType 仅支持 1/2/3")
        private Integer msgType;

        @Size(max = 4000, message = "contentText 长度不能超过 4000")
        private String contentText;

        private Long mediaId;
        private Long voiceMediaId;
    }

    @Data
    @Builder
    public static class SendMessageResponse {
        private Long id;
        private Integer seqNo;
    }

    /** 2.25 AI 流式补全 */
    @Data
    public static class CompletionRequest {
        @NotBlank(message = "clientMsgId 不能为空")
        @Size(max = 64, message = "clientMsgId 长度不能超过 64")
        private String clientMsgId;

        /** 要回复的用户消息 ID；缺省取最近一条用户消息 */
        private Long replyToMsgId;

        /** 固定 true（SSE），仅作契约兼容 */
        private Boolean stream;
    }

    /** SSE: event=delta */
    @Data
    @Builder
    public static class DeltaEvent {
        private String delta;
    }

    /** SSE: event=done（必须带完整正文，前端断流时可直接兜底） */
    @Data
    @Builder
    public static class DoneEvent {
        private Long assistantMsgId;
        private Long replyToMsgId;
        private String contentText;
        /** 1 正常 2 长度截断 3 内容过滤 4 错误 */
        private Integer finishReason;
        private String modelCode;
        private Integer promptTokens;
        private Integer completionTokens;
        private Long latencyMs;
    }

    /** SSE: event=error */
    @Data
    @Builder
    public static class ErrorEvent {
        private Integer code;
        private String message;
    }
}
