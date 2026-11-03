package com.threadfit.glucopilot.community.comment.service;

import static com.threadfit.glucopilot.community.comment.dto.CommentDtos.*;
import static com.threadfit.glucopilot.community.profile.dto.ProfileDtos.*;
import static com.threadfit.glucopilot.community.shared.support.CommunityValidation.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.comment.entity.CommunityComment;
import com.threadfit.glucopilot.community.comment.mapper.CommunityCommentMapper;
import com.threadfit.glucopilot.community.notification.service.CommunityEventService;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityCommentService {
    private final CommunityCommentMapper mapper;
    private final CommunityProfileService profiles;
    private final ArticleAccessService access;
    private final CommunityEventService events;

    public long commentCount(long newsId) {
        return mapper.countVisibleComments(newsId);
    }

    private Comment comment(long viewer, CommunityComment row) {
        return comment(viewer, row, true);
    }

    private Comment comment(long viewer, CommunityComment row, boolean includeReplies) {
        long user = row.getUserId(), id = row.getId(), news = row.getNewsId();
        Long root = row.getRootCommentId(), target = row.getReplyToCommentId();
        Profile targetAuthor = null;
        if (target != null) {
            var authors = mapper.selectReplyTargetAuthors(target, news);
            if (!authors.isEmpty())
                targetAuthor = profiles.profile(viewer, "u" + authors.getFirst());
        }
        Replies page =
                includeReplies && root == null
                        ? replyPage(viewer, news, id, null, 3)
                        : new Replies(List.of(), false, null);
        long total = includeReplies && root == null ? replyCount(news, id) : 0;
        return new Comment(
                Long.toString(id),
                row.getContent(),
                row.getCreatedAt(),
                profiles.profile(viewer, "u" + user),
                user == viewer,
                Long.toString(root == null ? id : root),
                target == null ? null : target.toString(),
                targetAuthor,
                page.records(),
                total,
                page.hasMore(),
                page.nextAfterId());
    }

    public PageResult<Comment> comments(long viewer, long news, PageQuery q) {
        profiles.requireUser(viewer);
        access.requireReadable(viewer, news);
        page(q);
        var rows =
                mapper.selectRootComments(news, q.getPageSize(), q.offset()).stream()
                        .map(rs -> comment(viewer, rs))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        long total = mapper.countRootComments(news);
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    /**
     * 「我的评论」：我自己发出的评论与回复，新的在前。
     *
     * <p>与 {@link #comments} 的两点不同，都是刻意的：
     *
     * <ol>
     *   <li><b>不过 {@code VISIBLE_COMMENT}</b>：被 AI 隐藏的评论作者自己必须看得见（否则等于 "发了却没有"，只会引发重发和咨询），前端据
     *       {@code hidden} 标注「已被系统隐藏」；
     *   <li><b>不要求文章仍可读</b>：文章下架/删除后评论仍是用户的历史，用 {@code articleAvailable} 告诉前端能不能点进去，而不是把记录整条吞掉。
     * </ol>
     *
     * 仍然排除自己删掉的评论（{@code is_deleted=1}）——那是用户主动删除，不该出现在"我发过的"里。
     */
    public PageResult<MyComment> myComments(long viewer, PageQuery q) {
        profiles.requireUser(viewer);
        page(q);
        long total = mapper.countOwnComments(viewer);
        var rows =
                mapper.selectOwnComments(viewer, q.getPageSize(), q.offset()).stream()
                        .map(
                                rs ->
                                        new MyComment(
                                                Long.toString(longValue(rs, "id")),
                                                longValue(rs, "news_id"),
                                                Objects.toString(text(rs, "title"), "文章已删除"),
                                                value(rs, "news_deleted") != null
                                                        && intValue(rs, "news_deleted") == 0
                                                        && value(rs, "publish_status") != null
                                                        && intValue(rs, "publish_status") == 1,
                                                text(rs, "content"),
                                                time(rs, "created_at"),
                                                value(rs, "root_comment_id") == null
                                                        ? null
                                                        : value(rs, "root_comment_id").toString(),
                                                value(rs, "reply_to_comment_id") == null
                                                        ? null
                                                        : value(rs, "reply_to_comment_id")
                                                                .toString(),
                                                intValue(rs, "is_hidden") == 1,
                                                longValue(rs, "reply_count"),
                                                longValue(rs, "like_count")))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    public CommentContext commentContext(long viewer, long news, long targetId) {
        profiles.requireUser(viewer);
        access.requireReadable(viewer, news);
        var targets =
                mapper.selectVisibleComment(targetId, news).stream()
                        .map(rs -> comment(viewer, rs, false))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (targets.isEmpty()) throw missing();
        var target = targets.getFirst();
        long rootId = Long.parseLong(target.rootCommentId());
        var roots =
                mapper.selectVisibleRoot(rootId, news).stream()
                        .map(rs -> comment(viewer, rs))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (roots.isEmpty()) throw missing();
        var root = roots.getFirst();
        if (targetId != rootId
                && root.replies().stream().noneMatch(reply -> reply.id().equals(target.id()))) {
            var replies = new ArrayList<>(root.replies());
            replies.add(target);
            replies.sort(Comparator.comparingLong(reply -> Long.parseLong(reply.id())));
            root =
                    new Comment(
                            root.id(),
                            root.content(),
                            root.createdAt(),
                            root.author(),
                            root.mine(),
                            root.rootCommentId(),
                            root.replyToCommentId(),
                            root.replyToAuthor(),
                            List.copyOf(replies),
                            root.replyCount(),
                            root.replyHasMore(),
                            root.replyNextAfterId());
        }
        return new CommentContext(target.id(), root);
    }

    private long replyCount(long news, long root) {
        return mapper.countReplies(news, root);
    }

    public Replies replies(long viewer, long news, long root, Long after, int size) {
        profiles.requireUser(viewer);
        access.requireReadable(viewer, news);
        if (size < 1 || size > 50 || (after != null && after < 1))
            throw new BizException(ErrorCode.BAD_REQUEST, "回复分页参数错误");
        if (mapper.countVisibleRoot(root, news) != 1) throw missing();
        return replyPage(viewer, news, root, after, size);
    }

    private Replies replyPage(long viewer, long news, long root, Long after, int size) {
        var rows =
                mapper.selectRepliesAfter(news, root, after, size + 1).stream()
                        .map(rs -> comment(viewer, rs, false))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean more = rows.size() > size;
        if (more) rows.removeLast();
        return new Replies(
                rows,
                more,
                rows.isEmpty() ? (after == null ? null : after.toString()) : rows.getLast().id());
    }

    private Long lockReplyTarget(long news, long target) {
        var targets = mapper.selectTargetRootIds(target, news);
        if (targets.isEmpty()) throw missing();
        long root = targets.getFirst() == null ? target : targets.getFirst();
        if (mapper.lockRootComment(root, news).isEmpty()) throw missing();
        if (target != root && mapper.lockReplyComment(target, news, root).isEmpty())
            throw missing();
        return root;
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    @Transactional
    public Comment addComment(long viewer, long news, CommentRequest request) {
        profiles.requireUser(viewer);
        access.requireReadable(viewer, news);
        String content = validateText(request.content(), 500, request.clientRequestId());
        Long target = request.replyToCommentId();
        if (target != null && target < 1) throw new BizException(ErrorCode.BAD_REQUEST, "回复目标不合法");
        var prior = mapper.selectCommentRequestIds(viewer, request.clientRequestId());
        if (prior.isEmpty()) {
            Long root = target == null ? null : lockReplyTarget(news, target);
            try {
                mapper.insertComment(
                        news, viewer, content, request.clientRequestId(), target, root);
            } catch (DuplicateKeyException ex) {
                /* Read the original row and verify payload below. */
            }
        }
        var rows = mapper.lockCommentRequest(viewer, request.clientRequestId());
        if (rows.isEmpty()) throw new BizException(ErrorCode.INTERNAL_ERROR, "评论保存失败，请重试");
        var row = rows.getFirst();
        if (row.getNewsId() != news
                || !content.equals(row.getContent())
                || row.getIsDeleted() != 0
                || !Objects.equals(target, row.getReplyToCommentId()))
            throw new BizException(ErrorCode.CONFLICT, "请求已使用，请刷新后重试");
        // 回查刚插入的这条时**不能**套用 VISIBLE_COMMENT：评论是"先发后审"，
        // AI 可能在插入与回查之间的几毫秒内把它判违规并置 is_hidden=1，
        // 那时套可见性过滤会查不到行，作者收到的是"内容或用户不存在"这种完全误导的 404。
        // 作者本人刚发的评论必须能拿到（前端据此立即渲染），列表侧仍然过滤隐藏。
        var saved =
                mapper.selectSavedComment(row.getId(), news).stream()
                        .map(rs -> comment(viewer, rs))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (saved.isEmpty()) throw missing();
        var recipients = new HashSet<Long>();
        Long author = events.articleUser(news);
        if (author != null) recipients.add(author);
        if (target != null) recipients.addAll(mapper.selectCommentAuthors(target, news));
        long commentId = row.getId();
        for (long recipient : recipients)
            events.notifyEvent(recipient, viewer, "COMMENT", commentId, news, commentId);
        return saved.getFirst();
    }

    @Transactional
    public void deleteComment(long viewer, long news, long comment) {
        profiles.requireUser(viewer);
        access.requireReadable(viewer, news);
        if (mapper.countOwnComment(comment, news, viewer) == 0) throw missing();
        mapper.softDeleteOwnComment(comment, news, viewer);
    }
}
