package com.threadfit.glucopilot.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** C 端反馈留言中心 DTO。 */
public final class FeedbackDtos {

    private FeedbackDtos() {}

    @Data
    public static class TypeItem {
        private Integer id;
        private String name;
        private Boolean deviceRelated;
    }

    @Data
    public static class SubmitRequest {
        /** 反馈类型表编号，5为设备支持。 */
        @NotNull
        @Min(1)
        @Max(255)
        private Integer feedbackType;

        @Positive private Long deviceId;

        @Size(max = 120)
        private String deviceDescription;

        @NotBlank
        @Size(max = 1000)
        private String contentText;
    }

    @Data
    @Builder
    public static class SubmitResult {
        private String id;
        private Integer replyStatus;
    }

    @Data
    @Builder
    public static class FeedbackItem {
        private String id;
        private Integer feedbackType;
        private String feedbackTypeName;
        private String deviceId;
        private String deviceDescription;
        private String contentText;

        /** 0已收到1处理中2已处理3已关闭 */
        private Integer replyStatus;

        /** 为空表示官方尚未回复，端上不展示回复区 */
        private String officialReply;

        private LocalDateTime repliedAt;
        private LocalDateTime createdAt;
    }
}
