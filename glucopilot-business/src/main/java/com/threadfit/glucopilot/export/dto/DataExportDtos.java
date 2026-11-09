package com.threadfit.glucopilot.export.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 导出任务的请求和返回对象，供用户端与管理端共同使用。 */
public final class DataExportDtos {
    private DataExportDtos() {}

    public record Create(LocalDate startDate, LocalDate endDate, String clientRequestId) {}

    public record Task(
            String id,
            String userId,
            String userName,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime createdAt,
            LocalDateTime finishedAt,
            String error,
            int glucoseCount,
            int mealCount) {}
}
