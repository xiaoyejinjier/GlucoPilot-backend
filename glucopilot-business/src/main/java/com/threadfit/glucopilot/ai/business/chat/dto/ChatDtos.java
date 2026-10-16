package com.threadfit.glucopilot.ai.business.chat.dto;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话接口出入参，对应《接口设计文档》2.23 / 2.24 / 2.25，以及 5.3 / 5.4（会话列表与删除）
 * 和"会话内历史读取"这条契约外补充接口。
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

        /**
         * 是否强制新建会话（默认 false）。
         * <p>
         * 契约里没有"新建会话"这条接口，而产品必须有"新对话"——所以在 2.23 上加这个开关，
         * 而不是新开一个路径：不传时行为与以前完全一致（复用进行中会话），传 true 时
         * 事务内"结束旧会话 + 建新会话"。这样老客户端不受影响，也不必改契约。
         */
        private Boolean forceNew;
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

    // ------------------------------------------------------------------ 会话生命周期（5.3 / 5.4 与历史读取）

    /** 5.3 会话列表出参（契约字段：title/last_msg_at/msg_count/is_pinned，另补 id 与状态供前端判断）。 */
    @Data
    @Builder
    public static class SessionListResponse {
        private List<SessionRow> records;
        private Long total;

        /** 归档阈值（天），前端据此解释"为什么这条被归档了"；与 ai.chat.archive-idle-days 同源。 */
        private Integer archiveIdleDays;
    }

    @Data
    @Builder
    public static class SessionRow {
        private Long id;
        private String title;
        /** 0 归档 1 进行中 2 已结束 */
        private Integer sessionStatus;
        private Integer sessionType;
        private Integer msgCount;
        private Boolean isPinned;
        private LocalDateTime lastMsgAt;
        private LocalDateTime createdAt;
        /** 最近一条消息的摘要（列表展示用；取不到时为 null，不影响列表）。 */
        private String lastMessagePreview;
    }

    /** 会话内历史的一条消息（读的是**全部历史**，与送进模型的记忆窗口无关，见服务层注释）。 */
    @Data
    @Builder
    public static class HistoryMessage {
        private Long id;
        private Integer seqNo;
        /** 1 用户 2 助手 3 系统 */
        private Integer msgRole;
        /** 1 文本 2 图片 3 语音 */
        private Integer msgType;
        private String contentText;
        private Long mediaId;
        /** 图片消息的可访问地址（由服务端解析，前端直接渲染；文本消息为 null）。 */
        private String mediaUrl;
        private Long voiceMediaId;
        private String modelCode;
        /** 1 正常 2 长度截断 3 内容过滤 4 错误 */
        private Integer finishReason;
        private LocalDateTime createdAt;
    }

    /** 历史分页出参。 */
    @Data
    @Builder
    public static class HistoryResponse {
        private List<HistoryMessage> records;
        /** 是否还有更早的消息（前端据此决定要不要继续上拉）。 */
        private Boolean hasMore;
        /** 本页最早一条的 seq_no，下一页的 beforeSeq 传它。 */
        private Integer nextBeforeSeq;
    }

    /** 置顶/取消置顶（契约只给了 is_pinned 字段，没有接口，这里补一个最小写接口）。 */
    @Data
    public static class PinnedRequest {
        @NotNull(message = "isPinned 不能为空")
        private Boolean isPinned;
    }
}
