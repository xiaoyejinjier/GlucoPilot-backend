package com.threadfit.glucopilot.community.access.service;

import static com.threadfit.glucopilot.community.access.dto.ArticleAccessDtos.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.access.mapper.ArticleAccessMapper;
import com.threadfit.glucopilot.community.profile.dto.ProfileDtos;

import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ArticleAccessService {
    private final ArticleAccessMapper mapper;

    public ArticleAccessService(ArticleAccessMapper mapper) {
        this.mapper = mapper;
    }

    private static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "文章不存在或不可查看");
    }

    private void user(long id) {
        if (mapper.countActiveUser(id) != 1) throw missing();
    }

    public void requireReadable(Long viewer, long id) {
        requireSource(viewer, id, true);
    }

    public void requireReportable(long viewer, long id) {
        requireSource(viewer, id, false);
    }

    private void requireSource(Long viewer, long id, boolean preferences) {
        if (viewer != null) user(viewer);
        if (mapper.countReadableArticle(id, viewer, preferences) != 1) throw missing();
    }

    public long views(long id) {
        return mapper.countViews(id);
    }

    public Policy policy(long id, boolean owner) {
        var rows = mapper.selectPolicy(id);
        if (rows.isEmpty()) return new Policy("PUBLIC", 0, List.of());
        var row = rows.getFirst();
        return new Policy(
                row.get("visibility").toString(),
                ((Number) row.get("revision")).intValue(),
                owner ? readers(id) : List.of());
    }

    private List<String> readers(long id) {
        return mapper.selectReaders(id).stream().map(Object::toString).toList();
    }

    public Scope normalize(long author, String visibility, List<String> raw) {
        String mode = visibility == null ? "PUBLIC" : visibility;
        if (!Set.of("PUBLIC", "PRIVATE", "FRIENDS").contains(mode))
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的可见范围");
        List<String> ids = raw == null ? List.of() : raw;
        if (ids.size() > 50
                || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,18}")))
            throw new BizException(ErrorCode.BAD_REQUEST, "最多选择50位好友，不能重复");
        if ((mode.equals("FRIENDS") && ids.isEmpty())
                || (!mode.equals("FRIENDS") && !ids.isEmpty()))
            throw new BizException(ErrorCode.BAD_REQUEST, "请为指定好友可见选择好友");
        var sorted = new TreeSet<Long>();
        for (String id : ids) {
            long peer;
            try {
                peer = Long.parseLong(id);
            } catch (NumberFormatException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, "好友标识不合法");
            }
            if (peer == author || mapper.countMutualFriend(peer, author) != 1)
                throw new BizException(ErrorCode.BAD_REQUEST, "所选用户已不是互相关注的好友，请重新选择");
            sorted.add(peer);
        }
        return new Scope(mode, sorted.stream().map(Object::toString).toList());
    }

    public PageResult<ProfileDtos.Profile> friends(long viewer, String keyword, PageQuery q) {
        user(viewer);
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
        String term = Objects.toString(keyword, "").trim();
        if (term.length() > 32) throw new BizException(ErrorCode.BAD_REQUEST, "昵称最多32字");
        long total = mapper.countFriends(viewer, term);
        var rows =
                mapper.selectFriends(viewer, term, q.getPageSize(), q.offset()).stream()
                        .map(
                                rs ->
                                        new ProfileDtos.Profile(
                                                "u" + longValue(rs, "id"),
                                                text(rs, "id"),
                                                Objects.toString(text(rs, "nickname"), "糖友"),
                                                Objects.toString(text(rs, "avatar_url"), ""),
                                                Objects.toString(text(rs, "bio"), ""),
                                                true,
                                                false))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    // Called inside the publication/draft transaction, after locking the owning article.
    public void saveScope(long id, Scope scope) {
        var current = policy(id, true);
        if (current.visibility().equals(scope.visibility())
                && current.viewerIds().equals(scope.viewerIds())) return;
        mapper.upsertPolicy(id, scope.visibility(), scope.visibility());
        mapper.deleteReaders(id);
        for (String reader : scope.viewerIds()) mapper.insertReader(id, Long.parseLong(reader));
    }

    @Transactional
    public Policy change(long viewer, long id, Change request) {
        user(viewer);
        var authors = mapper.lockAuthor(id);
        if (authors.isEmpty() || authors.getFirst() != viewer) throw missing();
        var scope = normalize(viewer, request.visibility(), request.viewerIds());
        var current = policy(id, true);
        boolean same =
                current.visibility().equals(scope.visibility())
                        && current.viewerIds().equals(scope.viewerIds());
        if (request.revision() != current.revision()
                && !(same && request.revision() == current.revision() - 1))
            throw new BizException(ErrorCode.CONFLICT, "可见范围已变化，请刷新后重试");
        saveScope(id, scope);
        return policy(id, true);
    }

    @Transactional
    public Map<String, Long> recordView(long viewer, long id) {
        requireReadable(viewer, id);
        if (mapper.countOwnArticle(id, viewer) == 0) mapper.insertDailyView(id, viewer);
        return Map.of("viewCount", views(id));
    }

    public void hideAfterReport(long viewer, long id) {
        mapper.insertHiddenArticle(viewer, id);
    }

    @Transactional
    public void hidden(long viewer, long id, boolean hidden) {
        user(viewer);
        if (!hidden) {
            mapper.deleteHiddenArticle(viewer, id);
            return;
        }
        if (mapper.countHiddenArticle(viewer, id) > 0) return;
        requireReadable(viewer, id);
        if (mapper.countOwnArticleForHiding(id, viewer) > 0)
            throw new BizException(ErrorCode.BAD_REQUEST, "自己的文章请使用可见范围设置");
        hideAfterReport(viewer, id);
    }
}
