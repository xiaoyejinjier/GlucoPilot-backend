package com.threadfit.glucopilot.community.report.service;

import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.report.mapper.CommunityReportManagementMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityReportService {
    private final CommunityReportManagementMapper mapper;
    private final CmsNewsMapper news;
    private final ArticleAccessService access;

    private BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "文章或举报不存在");
    }

    private BizException conflict() {
        return new BizException(ErrorCode.CONFLICT, "内容状态已变化，请刷新后重试");
    }

    private void user(long id, boolean lock) {
        var ids = mapper.selectActiveUser(id, lock);
        if (ids.isEmpty()) throw missing();
    }

    private void page(PageQuery q) {
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
    }

    @Transactional
    public Map<String, Object> report(long viewer, ReportRequest request) {
        user(viewer, true);
        String type = request.targetType();
        long id = request.targetId();
        if (!Set.of("ARTICLE", "COMMENT", "USER", "MESSAGE").contains(type)
                || !Set.of("MISINFORMATION", "SPAM", "ABUSE", "PRIVACY", "OTHER")
                        .contains(request.reason())
                || request.clientRequestId() == null
                || !request.clientRequestId().matches("[A-Za-z0-9_-]{8,64}")
                || Objects.toString(request.details(), "").length() > 500)
            throw new BizException(ErrorCode.BAD_REQUEST, "举报参数不合法");
        var existing = mapper.selectReportByRequest(viewer, request.clientRequestId());
        if (!existing.isEmpty()) {
            var old = existing.getFirst();
            if (!type.equals(old.get("target_type"))
                    || ((Number) old.get("target_id")).longValue() != id
                    || !request.reason().equals(old.get("reason"))
                    || !Objects.toString(request.details(), "").trim().equals(old.get("details")))
                throw conflict();
            return Map.of("id", old.get("id").toString(), "state", old.get("state"));
        }
        if ("ARTICLE".equals(type)) access.requireReportable(viewer, id);
        if ("COMMENT".equals(type) && mapper.countReportableComment(id) != 1) throw missing();
        if ("USER".equals(type)) {
            user(id, false);
            if (id == viewer) throw new BizException(ErrorCode.BAD_REQUEST, "不能举报自己");
        }
        if ("MESSAGE".equals(type) && mapper.countReportableMessage(id, viewer, viewer) != 1)
            throw missing();
        if (mapper.countOpenReports(viewer) >= 20)
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "待处理举报较多，请稍后再提交");
        if ("COMMENT".equals(type)) {
            Long newsId = mapper.selectCommentArticle(id);
            access.requireReportable(viewer, newsId);
        }
        String title = "ARTICLE".equals(type) ? oneText("ARTICLE_TITLE", id) : "";
        mapper.insertReport(
                viewer,
                type,
                id,
                request.reason(),
                Objects.toString(request.details(), "").trim(),
                request.clientRequestId(),
                title);
        if ("ARTICLE".equals(type)) access.hideAfterReport(viewer, id);
        var saved = mapper.selectSavedReport(viewer, request.clientRequestId());
        return Map.of("id", saved.get("id").toString(), "state", saved.get("state"));
    }

    public PageResult<Map<String, Object>> reports(String state, PageQuery q) {
        page(q);
        if (state != null
                && !state.isBlank()
                && !Set.of("OPEN", "DISMISSED", "RESOLVED").contains(state))
            throw new BizException(ErrorCode.BAD_REQUEST, "举报状态不合法");
        long total = mapper.countReports(state);
        var rows = mapper.selectReports(state, q.getPageSize(), q.offset());
        for (var row : rows) {
            String type = (String) row.get("target_type");
            long target = ((Number) row.get("target_id")).longValue();
            String context =
                    switch (type) {
                        case "ARTICLE" -> oneText("ARTICLE", target);
                        case "COMMENT" -> oneText("COMMENT", target);
                        case "MESSAGE" -> oneText("MESSAGE", target);
                        case "USER" -> "社区号 u" + target;
                        default -> "";
                    };
            row.put("target_content", context);
            row.put("id", row.get("id").toString());
            row.put("target_id", Long.toString(target));
            row.put("reporter_id", row.get("reporter_id").toString());
        }
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    private String oneText(String type, long id) {
        var rows = mapper.selectReportContext(type, id);
        return rows.isEmpty() ? "内容已不存在" : Objects.toString(rows.getFirst(), "");
    }

    @Transactional
    public void resolveReport(long admin, long id, Resolution request) {
        var rows = mapper.lockReport(id);
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        if (!"OPEN".equals(row.get("state"))) throw conflict();
        String action = request.action(), note = Objects.toString(request.note(), "").trim();
        if (note.isBlank()
                || note.length() > 1000
                || !Set.of("DISMISS", "HIDE_ARTICLE", "HIDE_COMMENT", "RESOLVE").contains(action))
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择处理方式并填写说明");
        long target = ((Number) row.get("target_id")).longValue();
        if (action.equals("HIDE_ARTICLE")) {
            if (!"ARTICLE".equals(row.get("target_type")))
                throw new BizException(ErrorCode.BAD_REQUEST, "举报目标不是文章");
            // 与点赞/收藏共用文章行锁，避免陈旧实体把下架状态写回。
            news.selectForAction(target);
            mapper.hideArticle(target);
            mapper.hideSubmission(target);
        }
        if (action.equals("HIDE_COMMENT")) {
            if (!"COMMENT".equals(row.get("target_type")))
                throw new BizException(ErrorCode.BAD_REQUEST, "举报目标不是评论");
            mapper.deleteReportedComment(target);
        }
        mapper.resolveReport(
                action.equals("DISMISS") ? "DISMISSED" : "RESOLVED", action, note, admin, id);
        log("REPORT", id, null, "ADMIN", admin, action, note);
    }

    public void log(
            String type,
            long id,
            Integer revision,
            String actor,
            Long actorId,
            String decision,
            String note) {
        mapper.insertModerationLog(type, id, revision, actor, actorId, decision, note);
    }
}
