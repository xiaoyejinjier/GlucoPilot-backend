package com.threadfit.glucopilot.community.comment.service;

import static com.threadfit.glucopilot.community.comment.dto.AdminCommentDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.comment.mapper.AdminCommunityCommentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminCommunityCommentService {
    private final AdminCommunityCommentMapper mapper;

    private void page(PageQuery q) {
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
    }

    /**
     * 管理端评论审核列表。
     *
     * <p>存在的理由：评论是"先发后审"，机器判违规会立刻 {@code is_hidden=1} 从公开列表消失； 没有这张列表，运营唯一能做的只有拿着 id
     * 调恢复接口——既找不到"今天被藏了哪些"， 也无法复查 {@code ERROR}（模型故障时按约定不隐藏，但也不等于通过）。
     *
     * <p>与公开列表的差别：不过 {@code VISIBLE_COMMENT}（要能看到被隐藏的），也不要求文章当前可读 （文章后续被下架不该让历史评论从审核视角消失）。
     *
     * @param status ai_status：SKIPPED（AI 未开启时的存量）/PENDING/REVIEWING/DONE，空=全部
     * @param verdict ai_verdict：PENDING/PASS/REJECT/ERROR，空=全部
     * @param hidden 是否被隐藏：1=被隐藏、0=可见，null=全部
     * @param keyword 评论正文或作者昵称模糊匹配
     */
    public PageResult<AdminComment> adminComments(
            String status, String verdict, Integer hidden, String keyword, PageQuery q) {
        page(q);
        if (status != null && !status.isBlank()) {
            if (!Set.of("SKIPPED", "PENDING", "REVIEWING", "DONE").contains(status))
                throw new BizException(ErrorCode.BAD_REQUEST, "审核状态不合法");
        }
        if (verdict != null && !verdict.isBlank()) {
            if (!Set.of("PENDING", "PASS", "REJECT", "ERROR").contains(verdict))
                throw new BizException(ErrorCode.BAD_REQUEST, "审核结论不合法");
        }
        if (hidden != null) {
            if (hidden != 0 && hidden != 1)
                throw new BizException(ErrorCode.BAD_REQUEST, "隐藏标记不合法");
        }
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            if (term.length() > 80) throw new BizException(ErrorCode.BAD_REQUEST, "搜索词最多80字");
        }
        long total =
                mapper.countAdminComments(
                        status, verdict, hidden, keyword == null ? null : keyword.trim());

        var rows =
                mapper
                        .selectAdminComments(
                                status,
                                verdict,
                                hidden,
                                keyword == null ? null : keyword.trim(),
                                q.getPageSize(),
                                q.offset())
                        .stream()
                        .map(
                                rs ->
                                        new AdminComment(
                                                Long.toString(longValue(rs, "id")),
                                                longValue(rs, "news_id"),
                                                text(rs, "title"),
                                                text(rs, "content"),
                                                "u" + longValue(rs, "user_id"),
                                                Objects.toString(text(rs, "nickname"), "社区用户"),
                                                text(rs, "ai_status"),
                                                text(rs, "ai_verdict"),
                                                text(rs, "ai_category"),
                                                integer(rs, "ai_severity"),
                                                integer(rs, "ai_confidence"),
                                                text(rs, "ai_reason"),
                                                text(rs, "ai_model"),
                                                text(rs, "ai_prompt_ver"),
                                                intValue(rs, "is_hidden") == 1,
                                                time(rs, "created_at")))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    /** 可空整数读取：{@code ai_severity}/{@code ai_confidence} 在"未审/通过"时是 NULL。 */
    private static Integer integer(Map<String, Object> rs, String field) {
        Object value = rs.get(field);
        return value == null ? null : ((Number) value).intValue();
    }
}
