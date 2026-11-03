package com.threadfit.glucopilot.community.notification.service;

import static com.threadfit.glucopilot.community.notification.dto.CommunityNotificationDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.notification.mapper.CommunityNotificationMapper;
import com.threadfit.glucopilot.community.shared.service.CommunityService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityNotificationService {
    private final CommunityNotificationMapper mapper;
    private final CommunityService community;

    private static final Set<String> KINDS = Set.of("LIKE", "COLLECT", "COMMENT", "FOLLOW");

    private void user(long id) {
        if (mapper.countActiveUser(id) != 1) throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
    }

    private void kind(String kind) {
        if (!KINDS.contains(Objects.toString(kind, "")))
            throw new BizException(ErrorCode.BAD_REQUEST, "提醒类别不正确");
    }

    public Summary summary(long viewer) {
        user(viewer);
        var direct = community.unread(viewer);
        var rows = mapper.selectUnreadCounts(viewer);
        Map<String, Long> counts = new HashMap<>();
        long latest = 0;
        for (var r : rows) {
            counts.put(r.get("kind").toString(), ((Number) r.get("total")).longValue());
            latest = Math.max(latest, ((Number) r.get("latest")).longValue());
        }
        long likes = counts.getOrDefault("LIKE", 0L),
                collections = counts.getOrDefault("COLLECT", 0L),
                comments = counts.getOrDefault("COMMENT", 0L),
                follows = counts.getOrDefault("FOLLOW", 0L);
        var feedback = mapper.selectReportFeedbackCounts(viewer);
        long reports = ((Number) feedback.get("total")).longValue();
        return new Summary(
                direct.unreadCount(),
                likes,
                collections,
                comments,
                follows,
                reports,
                direct.unreadCount() + likes + collections + comments + follows + reports,
                direct.latestMessageId(),
                direct.latestSenderId(),
                latest == 0 ? null : Long.toString(latest),
                feedback.get("latest") == null ? null : feedback.get("latest").toString());
    }

    public PageResult<Notice> list(long viewer, String kind, PageQuery q) {
        user(viewer);
        kind(kind);
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
        long total = mapper.countNotices(viewer, kind);
        var rows =
                mapper.selectNotices(viewer, kind, q.getPageSize(), q.offset()).stream()
                        .map(
                                rs ->
                                        new Notice(
                                                text(rs, "id"),
                                                text(rs, "kind"),
                                                community.profile(
                                                        viewer, "u" + longValue(rs, "actor_id")),
                                                text(rs, "news_id"),
                                                text(rs, "news_title"),
                                                text(rs, "comment_id"),
                                                Objects.toString(text(rs, "comment_content"), ""),
                                                time(rs, "created_at"),
                                                time(rs, "read_at") != null))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    @Transactional
    public void read(long viewer, Read p) {
        user(viewer);
        if (p == null) throw new BizException(ErrorCode.BAD_REQUEST, "请指定提醒范围");
        kind(p.kind());
        long through;
        try {
            through = Long.parseLong(p.throughId());
            if (through < 1) throw new NumberFormatException();
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提醒标识不正确");
        }
        if (mapper.countOwnedNotice(through, viewer, p.kind()) != 1)
            throw new BizException(ErrorCode.NOT_FOUND, "提醒不存在");
        mapper.markReadThrough(viewer, p.kind(), through);
    }
}
