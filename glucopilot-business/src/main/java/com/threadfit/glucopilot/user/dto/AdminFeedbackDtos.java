package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端反馈查询与审批 DTO。 */
public final class AdminFeedbackDtos {

    private AdminFeedbackDtos() {
    }

    @Data
    @Builder
    public static class FeedbackItem {
        private String id;
        private String userId;
        private String userName;
        /** 仅返回脱敏手机号。 */
        private String contact;
        private Integer feedbackType;
        private String contentText;
        private Integer replyStatus;
        private String officialReply;
        private LocalDateTime createdAt;
        private LocalDateTime repliedAt;
    }

    @Data
    public static class ReplyRequest {
        /** 0已收到1处理中2已处理3已关闭 */
        @NotNull
        @Min(0)
        @Max(3)
        private Integer replyStatus;

        @Size(max = 1000)
        private String officialReply;
    }

    @Data
    @Builder
    public static class ReplyResult {
        private Boolean updated;
    }
}
