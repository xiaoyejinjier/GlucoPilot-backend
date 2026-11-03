package com.threadfit.glucopilot.community.profile.service;

import static com.threadfit.glucopilot.community.profile.dto.ProfileDtos.*;
import static com.threadfit.glucopilot.community.shared.support.CommunityValidation.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.follow.mapper.CommunityFollowMapper;
import com.threadfit.glucopilot.community.profile.mapper.CommunityProfileMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityProfileService {
    private final CommunityProfileMapper mapper;
    private final CommunityFollowMapper followMapper;

    public void requireUser(long userId) {
        if (mapper.countActiveUser(userId) != 1) throw missing();
    }

    public boolean following(Long viewer, String key) {
        if (viewer == null) return false;
        if (key.startsWith("u"))
            return followMapper.countUserFollowing(viewer, key, Long.parseLong(key.substring(1)))
                    > 0;
        return followMapper.countBloggerFollowing(viewer, key) > 0;
    }

    public Profile profile(Long viewer, String key) {
        if (key == null || !key.matches("[ub][1-9][0-9]{0,18}")) throw missing();
        long id;
        try {
            id = Long.parseLong(key.substring(1));
        } catch (NumberFormatException ex) {
            throw missing();
        }
        if (key.charAt(0) == 'u') {
            var rows =
                    mapper.selectUserProfile(id).stream()
                            .map(
                                    rs ->
                                            new Profile(
                                                    key,
                                                    Long.toString(id),
                                                    text(rs, "nickname"),
                                                    text(rs, "avatar_url"),
                                                    text(rs, "bio"),
                                                    following(viewer, key),
                                                    Objects.equals(viewer, id)))
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (rows.isEmpty()) throw missing();
            return rows.getFirst();
        }
        var rows =
                mapper.selectBloggerProfile(id).stream()
                        .map(
                                rs -> {
                                    long linked = longValue(rs, "linked_id");
                                    String user =
                                            value(rs, "linked_id") == null
                                                    ? null
                                                    : Long.toString(linked);
                                    return new Profile(
                                            key,
                                            user,
                                            text(rs, "nickname"),
                                            text(rs, "avatar_url"),
                                            text(rs, "bio"),
                                            following(viewer, key),
                                            user != null && Objects.equals(viewer, linked));
                                })
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (rows.isEmpty()) throw missing();
        var publisher = rows.getFirst();
        return publisher.userId() == null ? publisher : profile(viewer, "u" + publisher.userId());
    }

    public Profile newsAuthor(Long viewer, long newsId) {
        var linked = mapper.selectArticleUsers(newsId);
        if (!linked.isEmpty()) return profile(viewer, "u" + linked.getFirst());
        Long blogger = mapper.selectArticleBlogger(newsId);
        try {
            return profile(viewer, "b" + blogger);
        } catch (BizException ex) {
            return null;
        }
    }

    public PageResult<Profile> search(long viewer, String keyword, PageQuery q) {
        requireUser(viewer);
        page(q);
        String term = keyword == null ? "" : keyword.trim();
        if (term.isEmpty() || term.length() > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入1～32字的昵称");
        }
        // Literal substring search; no phone/openid matching and no SQL wildcard
        // interpretation.
        long total = mapper.countProfileSearch(viewer, term);
        var ids = mapper.selectProfileSearchIds(viewer, term, q.getPageSize(), q.offset());
        return PageResult.of(
                q.getPageNum(),
                q.getPageSize(),
                total,
                ids.stream().map(id -> profile(viewer, "u" + id)).toList());
    }
}
