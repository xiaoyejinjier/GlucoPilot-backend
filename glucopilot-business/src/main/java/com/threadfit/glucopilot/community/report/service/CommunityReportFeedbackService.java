package com.threadfit.glucopilot.community.report.service;

import static com.threadfit.glucopilot.community.report.dto.CommunityReportFeedbackDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.report.mapper.CommunityReportMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommunityReportFeedbackService {
    private final CommunityReportMapper mapper;

    private void user(long id) {
        if (mapper.countActiveUser(id) != 1) throw missing();
    }

    private static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "举报记录不存在");
    }

    private static Feedback map(Map<String, Object> rs, int row) {
        return new Feedback(
                text(rs, "id"),
                text(rs, "target_type"),
                text(rs, "target_id"),
                text(rs, "target_title"),
                text(rs, "reason"),
                text(rs, "details"),
                text(rs, "state"),
                text(rs, "resolution"),
                text(rs, "resolution_note"),
                time(rs, "created_at"),
                time(rs, "resolved_at"),
                time(rs, "feedback_read_at") != null);
    }

    public PageResult<Feedback> mine(long viewer, PageQuery q) {
        user(viewer);
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
        long total = mapper.countOwnedReports(viewer);
        var rows =
                mapper.selectOwnedReports(viewer, q.getPageSize(), q.offset()).stream()
                        .map(row -> map(row, 0))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    public Feedback get(long viewer, long id) {
        user(viewer);
        var rows =
                mapper.selectOwnedReport(id, viewer).stream()
                        .map(row -> map(row, 0))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    public void read(long viewer, long id) {
        get(viewer, id);
        mapper.markFeedbackRead(id, viewer);
    }
}
