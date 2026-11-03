package com.threadfit.glucopilot.community.reaction.service;

import static com.threadfit.glucopilot.community.shared.support.CommunityValidation.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;
import com.threadfit.glucopilot.community.reaction.mapper.CommunityReactionMapper;
import com.threadfit.glucopilot.content.dto.ContentDtos;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityReactionService {
    private final CommunityReactionMapper mapper;
    private final CommunityProfileService profiles;
    private final ArticleAccessService access;

    public PageResult<ContentDtos.NewsListItem> collections(long viewer, PageQuery q) {
        return collections(viewer, null, q);
    }

    public PageResult<ContentDtos.NewsListItem> collections(
            long viewer, String keyword, PageQuery q) {
        return actionArticles(viewer, keyword, q, false);
    }

    public PageResult<ContentDtos.NewsListItem> likes(long viewer, String keyword, PageQuery q) {
        return actionArticles(viewer, keyword, q, true);
    }

    private PageResult<ContentDtos.NewsListItem> actionArticles(
            long viewer, String keyword, PageQuery q, boolean liked) {
        profiles.requireUser(viewer);
        page(q);
        String term = listKeyword(keyword, 80);
        long total = mapper.countActionArticles(viewer, term, liked);
        var rows =
                mapper
                        .selectActionArticles(viewer, term, q.getPageSize(), q.offset(), liked)
                        .stream()
                        .map(
                                rs ->
                                        ContentDtos.NewsListItem.builder()
                                                .id(text(rs, "id"))
                                                .title(text(rs, "title"))
                                                .summaryText(text(rs, "summary_text"))
                                                .channelCode(intValue(rs, "channel_code"))
                                                .contentType(intValue(rs, "content_type"))
                                                .coverUrl(text(rs, "cover_url"))
                                                .publishAt(time(rs, "publish_at"))
                                                .likeCount(intValue(rs, "like_count"))
                                                .collectCount(intValue(rs, "collect_count"))
                                                .viewCount(access.views(longValue(rs, "id")))
                                                .build())
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }
}
