package com.threadfit.glucopilot.glucose.alert.repository;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.glucose.alert.dto.GlucoseAlertDtos;
import com.threadfit.glucopilot.glucose.alert.support.GlucoseAlertPolicy;
import com.threadfit.glucopilot.glucose.entity.GlucoseAlertRows;
import com.threadfit.glucopilot.glucose.mapper.GlucoseAlertMapper;
import com.threadfit.glucopilot.glucose.mapper.GlucoseAlertQuery;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 异常血糖的类型转换入口，所有SQL和行锁交给MyBatis Mapper。 */
@Repository
@RequiredArgsConstructor
public class GlucoseAlertRepository {
    private final GlucoseAlertMapper mapper;

    public record Filter(
            LocalDate start, LocalDate end, String keyword, String alertType, String status) {}

    public Map<String, Long> overview(Filter filter) {
        var row = mapper.selectOverview(query(filter));
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("total", amount(row.getTotal()));
        values.put("lowCount", amount(row.getLowCount()));
        values.put("highCount", amount(row.getHighCount()));
        values.put("pendingCount", amount(row.getPendingCount()));
        values.put("processingCount", amount(row.getProcessingCount()));
        values.put("resolvedCount", amount(row.getResolvedCount()));
        values.put("closedCount", amount(row.getClosedCount()));
        return values;
    }

    public List<GlucoseAlertDtos.Event> page(Filter filter, PageQuery page) {
        return mapper.selectPage(query(filter), page.getPageSize(), page.offset()).stream()
                .map(GlucoseAlertRepository::event)
                .toList();
    }

    public GlucoseAlertDtos.Event find(long id) {
        var row = mapper.selectEvent(id);
        return row == null ? null : event(row);
    }

    /** 同一Spring事务内锁定原血糖记录，更新/删除和处理事件使用相同行锁。 */
    public boolean lockSource(long id) {
        return !mapper.lockSource(id).isEmpty();
    }

    public int save(
            GlucoseAlertDtos.Event event,
            long opsId,
            String status,
            String note,
            String userReply,
            LocalDateTime repliedAt,
            LocalDateTime now) {
        var change = change(event, opsId, status, note, userReply, repliedAt, now, null);
        return event.version() == 0 ? mapper.insertCase(change) : mapper.updateCase(change);
    }

    public void appendLog(
            GlucoseAlertDtos.Event event,
            long opsId,
            String status,
            String note,
            String userReply,
            LocalDateTime now,
            String requestId) {
        mapper.insertHistory(change(event, opsId, status, note, userReply, null, now, requestId));
    }

    public List<GlucoseAlertDtos.History> history(long id) {
        return mapper.selectHistory(id).stream()
                .map(
                        row ->
                                new GlucoseAlertDtos.History(
                                        stringId(row.getId()),
                                        row.getGlucoseValue(),
                                        row.getRecordedAt(),
                                        row.getFromStatus(),
                                        row.getToStatus(),
                                        row.getNote(),
                                        stringId(row.getOperatorId()),
                                        row.getOperatedAt(),
                                        row.getRequestId(),
                                        row.getUserReply()))
                .toList();
    }

    public PageResult<GlucoseAlertDtos.UserReply> userReplies(long userId, PageQuery page) {
        long total = mapper.countUserReplies(userId);
        var rows =
                mapper.selectUserReplies(userId, page.getPageSize(), page.offset()).stream()
                        .map(
                                row ->
                                        new GlucoseAlertDtos.UserReply(
                                                stringId(row.getId()),
                                                stringId(row.getRecordId()),
                                                row.getRecordDate(),
                                                row.getNodeCode(),
                                                GlucoseAlertPolicy.nodeName(row.getNodeCode()),
                                                row.getGlucoseValue(),
                                                row.getRecordedAt(),
                                                row.getUserReply(),
                                                row.getRepliedAt()))
                        .toList();
        return PageResult.of(page.getPageNum(), page.getPageSize(), total, rows);
    }

    private static GlucoseAlertQuery query(Filter filter) {
        return new GlucoseAlertQuery(
                filter.start(),
                filter.end(),
                filter.keyword(),
                filter.alertType(),
                filter.status());
    }

    private static GlucoseAlertDtos.Event event(GlucoseAlertRows.Event row) {
        return new GlucoseAlertDtos.Event(
                stringId(row.getId()),
                stringId(row.getUserId()),
                row.getUsername(),
                row.getNickname(),
                maskPhone(row.getPhone()),
                row.getRecordDate(),
                row.getNodeCode(),
                GlucoseAlertPolicy.nodeName(row.getNodeCode()),
                row.getGlucoseValue(),
                row.getSourceType() == null ? 0 : row.getSourceType(),
                row.getRemark(),
                row.getRecordedAt(),
                GlucoseAlertPolicy.classify(row.getNodeCode(), row.getGlucoseValue()),
                row.getStatus(),
                row.getNote(),
                amount(row.getVersion()),
                stringId(row.getHandledBy()),
                row.getHandledAt(),
                row.getUserReply(),
                row.getRepliedAt());
    }

    private static GlucoseAlertRows.Change change(
            GlucoseAlertDtos.Event event,
            long opsId,
            String status,
            String note,
            String userReply,
            LocalDateTime repliedAt,
            LocalDateTime now,
            String requestId) {
        return new GlucoseAlertRows.Change(
                Long.parseLong(event.id()),
                event.glucoseValue(),
                event.recordedAt(),
                event.version(),
                event.status(),
                status,
                note,
                opsId,
                now,
                userReply,
                repliedAt,
                requestId);
    }

    private static long amount(Long value) {
        return value == null ? 0 : value;
    }

    private static String stringId(Long value) {
        return value == null ? null : value.toString();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        return phone.length() < 8
                ? "***"
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
