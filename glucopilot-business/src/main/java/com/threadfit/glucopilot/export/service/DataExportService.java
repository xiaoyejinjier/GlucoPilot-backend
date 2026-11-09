package com.threadfit.glucopilot.export.service;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.export.dto.DataExportDtos.Create;
import com.threadfit.glucopilot.export.dto.DataExportDtos.Task;
import com.threadfit.glucopilot.export.entity.DataExportTaskRow;
import com.threadfit.glucopilot.export.mapper.DataExportMapper;
import com.threadfit.glucopilot.export.mapper.DataExportQuery;
import com.threadfit.glucopilot.export.support.ExportPdf;

import lombok.RequiredArgsConstructor;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 导出任务业务；数据库访问统一交给MyBatis Mapper，事务覆盖锁、状态和文件写入。 */
@Service
@RequiredArgsConstructor
public class DataExportService {
    private final DataExportMapper mapper;

    public static final String MIME = "application/pdf";

    private BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "导出任务不存在或不可访问");
    }

    /** 小程序删除只隐藏本人任务，保留管理端任务和已生成文件。 */
    @Transactional
    public void remove(long user, long id) {
        if (mapper.lockOwnedTask(user, id).isEmpty()) {
            throw missing();
        }
        mapper.hideForUser(user, id);
    }

    private Task map(DataExportTaskRow row) {
        LocalDateTime created = row.getCreatedAt();
        LocalDate end = row.getEndDate() == null ? created.toLocalDate() : row.getEndDate();
        // 兼容旧的近7天/近30天任务；新任务始终保存明确的日期区间。
        LocalDate start =
                row.getStartDate() == null
                        ? end.minusDays(row.getExportScope() == 1 ? 6 : 29)
                        : row.getStartDate();
        String status =
                switch (row.getTaskStatus()) {
                    case 1 -> "processing";
                    case 2 -> "done";
                    case 3 -> "failed";
                    default -> "pending";
                };
        return new Task(
                row.getId().toString(),
                row.getUserId().toString(),
                row.getUserName(),
                status,
                start,
                end,
                created,
                row.getFinishedAt(),
                Objects.toString(row.getErrorMsg(), ""),
                row.getGlucoseCount() == null ? 0 : row.getGlucoseCount(),
                row.getMealCount() == null ? 0 : row.getMealCount());
    }

    public Task get(Long viewer, long id) {
        var row = mapper.selectTask(viewer, id);
        if (row == null) {
            throw missing();
        }
        return map(row);
    }

    /** 用户端传当前登录ID；管理端鉴权后传null，仍可查看被用户隐藏的任务。 */
    public PageResult<Task> list(Long viewer, String status, String keyword, PageQuery q) {
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 100) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分页参数不正确");
        }
        Integer statusCode = null;
        if (status != null && !status.isBlank()) {
            int index = List.of("pending", "processing", "done", "failed").indexOf(status);
            if (index < 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "任务状态不正确");
            }
            statusCode = index;
        }
        String term = null;
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 64) {
                throw new BizException(ErrorCode.BAD_REQUEST, "关键词最多64字");
            }
            term = keyword.trim();
        }
        var query = new DataExportQuery(viewer, statusCode, term);
        long total = mapper.countTasks(query);
        var rows =
                mapper.selectTasks(query, q.getPageSize(), q.offset()).stream()
                        .map(this::map)
                        .toList();
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    @Transactional
    public Task create(long user, Create p) {
        if (p == null
                || p.startDate() == null
                || p.endDate() == null
                || p.startDate().isAfter(p.endDate())
                || p.endDate().isAfter(LocalDate.now())
                || ChronoUnit.DAYS.between(p.startDate(), p.endDate()) > 365
                || p.clientRequestId() == null
                || !p.clientRequestId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择最多366天的有效历史日期");
        }

        // 先锁账号，再检查幂等和额度，防止并发申请绕过每天20次的限制。
        if (mapper.lockActiveUser(user).isEmpty()) {
            throw missing();
        }
        var prior = mapper.selectRequest(user, p.clientRequestId());
        if (prior != null) {
            if (prior.getUserDeletedAt() != null) {
                throw new BizException(ErrorCode.CONFLICT, "该导出任务已移除，请重新发起导出");
            }
            var saved = get(user, prior.getId());
            if (!saved.startDate().equals(p.startDate()) || !saved.endDate().equals(p.endDate())) {
                throw new BizException(ErrorCode.CONFLICT, "导出请求已使用，请重新确认");
            }
            return saved;
        }
        if (mapper.countRecentTasks(user, LocalDateTime.now().minusHours(24)) >= 20) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "今日导出申请较多，请稍后再试");
        }
        mapper.insertTask(user, p.startDate(), p.endDate(), p.clientRequestId());
        return get(user, mapper.selectRequest(user, p.clientRequestId()).getId());
    }

    @Transactional
    public Task generate(Long viewer, long id) {
        if (mapper.lockTask(viewer, id).isEmpty()) {
            throw missing();
        }
        var task = get(viewer, id);
        // 已完成任务返回同一快照，避免后来修改健康记录影响旧导出文件。
        if (task.status().equals("done") && mapper.countFiles(id) > 0) {
            return task;
        }
        mapper.markProcessing(id);
        try {
            long user = Long.parseLong(task.userId());
            var glucose = mapper.selectGlucoseRecords(user, task.startDate(), task.endDate());
            var meals = mapper.selectMealRecords(user, task.startDate(), task.endDate());
            if (glucose.size() > 20000 || meals.size() > 20000) {
                throw new BizException(ErrorCode.BAD_REQUEST, "记录超过单次20000条，请缩小日期范围");
            }
            byte[] bytes = ExportPdf.create(task, glucose, meals);
            mapper.deleteFile(id);
            mapper.insertFile(id, bytes, glucose.size(), meals.size());
            mapper.markDone(id, "血糖记录 " + glucose.size() + " 条，饮食记录 " + meals.size() + " 条");
        } catch (Exception ex) {
            String message = ex instanceof BizException ? ex.getMessage() : "文件生成失败，请稍后重试";
            mapper.markFailed(id, message);
        }
        return get(viewer, id);
    }

    /** 账号注销：先删除文件快照，再删除该用户的导出任务。 */
    @Transactional
    public void purgeAccountData(long user) {
        mapper.purgeFilesByUser(user);
        mapper.purgeTasksByUser(user);
    }

    public byte[] file(Long viewer, long id) {
        var task = get(viewer, id);
        if (!task.status().equals("done")) {
            throw new BizException(ErrorCode.CONFLICT, "导出文件尚未生成，请稍后再试");
        }
        var file = mapper.selectFile(id);
        if (file == null) {
            throw missing();
        }
        byte[] stored = file.getFileBytes();
        if (stored.length >= 5
                && stored[0] == '%'
                && stored[1] == 'P'
                && stored[2] == 'D'
                && stored[3] == 'F'
                && stored[4] == '-') {
            return stored;
        }
        // 旧Excel只从已保存的快照转换，绝不重新查询当前健康数据或覆盖原文件。
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(stored))) {
            return ExportPdf.create(
                    task,
                    legacy(
                            book,
                            "血糖记录",
                            new String[] {
                                "record_date",
                                "node_code",
                                "glucose_value",
                                "source_type",
                                "recorded_at",
                                "remark"
                            }),
                    legacy(
                            book,
                            "饮食记录",
                            new String[] {
                                "record_date",
                                "node_code",
                                "foods_text",
                                "carbs_g",
                                "hunger_level",
                                "supplement_text",
                                "recorded_at"
                            }));
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "历史导出文件暂不可用，请重新发起导出");
        }
    }

    private List<Map<String, Object>> legacy(XSSFWorkbook book, String name, String[] keys) {
        var sheet = book.getSheet(name);
        if (sheet == null || sheet.getLastRowNum() > 20000) {
            throw new IllegalArgumentException("Invalid export snapshot");
        }
        var rows = new ArrayList<Map<String, Object>>();
        var formatter = new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
        for (int at = 1; at <= sheet.getLastRowNum(); at++) {
            var row = sheet.getRow(at);
            if (row == null) {
                continue;
            }
            var result = new LinkedHashMap<String, Object>();
            for (int col = 0; col < keys.length; col++) {
                result.put(keys[col], formatter.formatCellValue(row.getCell(col)));
            }
            rows.add(result);
        }
        return rows;
    }
}
