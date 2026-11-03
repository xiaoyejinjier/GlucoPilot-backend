package com.threadfit.glucopilot.glucose.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 异常事件查询和写入的类型化投影；不直接作为用户端响应。 */
public final class GlucoseAlertRows {
    private GlucoseAlertRows() {}

    @Data
    public static class Event {
        private Long id;
        private Long userId;
        private String username;
        private String nickname;
        private String phone;
        private LocalDate recordDate;
        private String nodeCode;
        private BigDecimal glucoseValue;
        private Integer sourceType;
        private String remark;
        private LocalDateTime recordedAt;
        private String status;
        private String note;
        private Long version;
        private Long handledBy;
        private LocalDateTime handledAt;
        private String userReply;
        private LocalDateTime repliedAt;
    }

    @Data
    public static class Overview {
        private Long total;
        private Long lowCount;
        private Long highCount;
        private Long pendingCount;
        private Long processingCount;
        private Long resolvedCount;
        private Long closedCount;
    }

    @Data
    public static class History {
        private Long id;
        private BigDecimal glucoseValue;
        private LocalDateTime recordedAt;
        private String fromStatus;
        private String toStatus;
        private String note;
        private Long operatorId;
        private LocalDateTime operatedAt;
        private String requestId;
        private String userReply;
    }

    @Data
    public static class UserReply {
        private Long id;
        private Long recordId;
        private LocalDate recordDate;
        private String nodeCode;
        private BigDecimal glucoseValue;
        private LocalDateTime recordedAt;
        private String userReply;
        private LocalDateTime repliedAt;
    }

    public record Change(
            long recordId,
            BigDecimal glucoseValue,
            LocalDateTime recordedAt,
            long version,
            String fromStatus,
            String toStatus,
            String note,
            long operatorId,
            LocalDateTime operatedAt,
            String userReply,
            LocalDateTime repliedAt,
            String requestId) {}
}
