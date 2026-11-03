package com.threadfit.glucopilot.community.media.service;

import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.community.media.mapper.CommunityArticlePresentationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityArticlePresentationService {
    private final CommunityArticlePresentationMapper mapper;

    public List<PublicationDtos.ArticleImage> articleImages(long newsId) {
        return mapper.selectArticleImages(newsId).stream()
                .map(rs -> new PublicationDtos.ArticleImage(text(rs, "media_id")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public List<PublicationDtos.ArticleImage> articleVideos(long newsId) {
        return mapper.selectArticleVideos(newsId).stream()
                .map(rs -> new PublicationDtos.ArticleImage(text(rs, "media_id")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** 资讯详情标签；无投稿标签时返回空列表。 */
    public List<PublicationDtos.Tag> articleTags(long newsId) {
        return mapper.selectArticleTags(newsId).stream()
                .map(rs -> new PublicationDtos.Tag(text(rs, "code"), text(rs, "name")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
