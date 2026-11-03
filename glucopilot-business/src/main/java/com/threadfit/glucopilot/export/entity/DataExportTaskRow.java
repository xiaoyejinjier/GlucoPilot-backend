package com.threadfit.glucopilot.export.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 导出任务查询投影，包含关联的用户名称和已生成文件计数。 */
@Data
public class DataExportTaskRow {
    private Long id;
    private Long userId;
    private String userName;
    private Integer exportScope;
    private Integer taskStatus;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private LocalDateTime userDeletedAt;
    private String errorMsg;
    private Integer glucoseCount;
    private Integer mealCount;
}
