package com.threadfit.glucopilot.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.reaction.entity.NewsUserAction;
import com.threadfit.glucopilot.community.reaction.mapper.NewsUserActionMapper;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import com.threadfit.glucopilot.content.entity.CmsBanner;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsBannerMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ContentService {

    private static final int SHELF_ON = 1;
    private static final int PUBLISHED = 1;

    private final com.threadfit.glucopilot.community.reaction.service.CommunityArticleActionService
            articleActions;
    private final CmsBannerMapper bannerMapper;
    private final CmsNewsMapper newsMapper;
    private final NewsUserActionMapper newsUserActionMapper;
    private final com.threadfit.glucopilot.community.shared.service.CommunityService community;

    public ContentService(
            CmsBannerMapper bannerMapper,
            CmsNewsMapper newsMapper,
            NewsUserActionMapper newsUserActionMapper,
            com.threadfit.glucopilot.community.shared.service.CommunityService community,
            com.threadfit.glucopilot.community.reaction.service.CommunityArticleActionService
                    articleActions) {
        this.articleActions = articleActions;
        this.bannerMapper = bannerMapper;
        this.newsMapper = newsMapper;
        this.newsUserActionMapper = newsUserActionMapper;
        this.community = community;
    }

    public List<ContentDtos.BannerItem> listBanners() {
        LocalDateTime now = LocalDateTime.now();
        List<CmsBanner> rows =
                bannerMapper.selectList(
                        new LambdaQueryWrapper<CmsBanner>()
                                .eq(CmsBanner::getShelfStatus, SHELF_ON)
                                .and(
                                        w ->
                                                w.isNull(CmsBanner::getStartAt)
                                                        .or()
                                                        .le(CmsBanner::getStartAt, now))
                                .and(
                                        w ->
                                                w.isNull(CmsBanner::getEndAt)
                                                        .or()
                                                        .ge(CmsBanner::getEndAt, now))
                                .orderByAsc(CmsBanner::getSortNo)
                                .orderByDesc(CmsBanner::getId));
        return rows.stream().map(this::toBanner).toList();
    }

    public PageResult<ContentDtos.NewsListItem> pageNews(Integer channelCode, PageQuery query) {
        return pageNews(channelCode, query, null);
    }

    public PageResult<ContentDtos.NewsListItem> pageNews(
            Integer channelCode, PageQuery query, Long viewer) {
        Page<CmsNews> page =
                newsMapper.selectPage(
                        new Page<>(query.getPageNum(), query.getPageSize()),
                        new LambdaQueryWrapper<CmsNews>()
                                .eq(CmsNews::getPublishStatus, PUBLISHED)
                                .le(CmsNews::getPublishAt, LocalDateTime.now())
                                .eq(channelCode != null, CmsNews::getChannelCode, channelCode)
                                .apply(
                                        com.threadfit.glucopilot.community.access.mapper
                                                .ArticleAccessSql.publishedSql("cms_news"))
                                .apply(
                                        com.threadfit.glucopilot.community.access.mapper
                                                .ArticleAccessSql.readableSql("cms_news", viewer))
                                .orderByDesc(CmsNews::getPublishAt)
                                .orderByDesc(CmsNews::getId));
        Page<ContentDtos.NewsListItem> mapped =
                new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        mapped.setRecords(page.getRecords().stream().map(this::toNewsListItem).toList());
        return PageResult.of(mapped);
    }

    public ContentDtos.NewsDetail getNewsDetail(Long newsId, Long userIdOrNull) {
        community.requireReadable(userIdOrNull, newsId);
        CmsNews news = requirePublishedNews(newsId);
        Boolean liked = null;
        Boolean collected = null;
        if (userIdOrNull != null) {
            NewsUserAction action = findAction(userIdOrNull, newsId);
            if (action != null) {
                liked = action.getIsLiked() != null && action.getIsLiked() == 1;
                collected = action.getIsCollected() != null && action.getIsCollected() == 1;
            } else {
                liked = false;
                collected = false;
            }
        }
        return ContentDtos.NewsDetail.builder()
                .id(String.valueOf(news.getId()))
                .title(news.getTitle())
                .summaryText(news.getSummaryText())
                .channelCode(news.getChannelCode())
                .contentType(news.getContentType())
                .coverUrl(news.getCoverUrl())
                .videoUrl(news.getVideoUrl())
                .contentBody(news.getContentBody())
                .images(community.articleImages(newsId))
                .videos(community.articleVideos(newsId))
                .tags(community.articleTags(newsId))
                .publishAt(news.getPublishAt())
                .likeCount(news.getLikeCount())
                .collectCount(news.getCollectCount())
                .liked(liked)
                .collected(collected)
                .author(community.newsAuthor(userIdOrNull, newsId))
                .commentCount(community.commentCount(newsId))
                .viewCount(community.viewCount(newsId))
                .build();
    }

    /** 资讯调用方兼容入口；实际点赞/收藏逻辑位于社区reaction模块。 */
    public com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionResult
            updateNewsAction(
                    Long userId,
                    Long newsId,
                    com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionRequest
                            request) {
        return articleActions.updateNewsAction(userId, newsId, request);
    }

    private CmsNews requirePublishedNews(Long newsId) {
        CmsNews news = newsMapper.selectById(newsId);
        if (news == null
                || news.getPublishStatus() == null
                || news.getPublishStatus() != PUBLISHED
                || (news.getPublishAt() != null
                        && news.getPublishAt().isAfter(LocalDateTime.now()))) {
            throw new BizException(ErrorCode.NOT_FOUND, "资讯不存在");
        }
        return news;
    }

    private NewsUserAction findAction(Long userId, Long newsId) {
        return newsUserActionMapper.selectOne(
                new LambdaQueryWrapper<NewsUserAction>()
                        .eq(NewsUserAction::getUserId, userId)
                        .eq(NewsUserAction::getNewsId, newsId)
                        .last("LIMIT 1"));
    }

    private ContentDtos.BannerItem toBanner(CmsBanner b) {
        return ContentDtos.BannerItem.builder()
                .id(String.valueOf(b.getId()))
                .title(b.getTitle())
                .subtitle(b.getSubtitle())
                .imageUrl(b.getImageUrl())
                .linkUrl(b.getLinkUrl())
                .sortNo(b.getSortNo())
                .build();
    }

    private ContentDtos.NewsListItem toNewsListItem(CmsNews n) {
        return ContentDtos.NewsListItem.builder()
                .id(String.valueOf(n.getId()))
                .title(n.getTitle())
                .summaryText(n.getSummaryText())
                .channelCode(n.getChannelCode())
                .contentType(n.getContentType())
                .coverUrl(n.getCoverUrl())
                .publishAt(n.getPublishAt())
                .likeCount(n.getLikeCount())
                .collectCount(n.getCollectCount())
                .viewCount(community.viewCount(n.getId()))
                .build();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
