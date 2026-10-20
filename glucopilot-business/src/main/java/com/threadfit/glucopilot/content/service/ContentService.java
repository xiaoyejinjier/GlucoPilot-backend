package com.threadfit.glucopilot.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import com.threadfit.glucopilot.content.entity.CmsBanner;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.entity.NewsUserAction;
import com.threadfit.glucopilot.content.mapper.CmsBannerMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import com.threadfit.glucopilot.content.mapper.NewsUserActionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ContentService {

    private static final int SHELF_ON = 1;
    private static final int PUBLISHED = 1;

    private final CmsBannerMapper bannerMapper;
    private final CmsNewsMapper newsMapper;
    private final NewsUserActionMapper newsUserActionMapper;
    private final com.threadfit.glucopilot.community.CommunityService community;

    public ContentService(CmsBannerMapper bannerMapper, CmsNewsMapper newsMapper,
                          NewsUserActionMapper newsUserActionMapper,
                          com.threadfit.glucopilot.community.CommunityService community) {
        this.bannerMapper = bannerMapper;
        this.newsMapper = newsMapper;
        this.newsUserActionMapper = newsUserActionMapper;
        this.community = community;
    }

    public List<ContentDtos.BannerItem> listBanners() {
        LocalDateTime now = LocalDateTime.now();
        List<CmsBanner> rows = bannerMapper.selectList(new LambdaQueryWrapper<CmsBanner>()
                .eq(CmsBanner::getShelfStatus, SHELF_ON)
                .and(w -> w.isNull(CmsBanner::getStartAt).or().le(CmsBanner::getStartAt, now))
                .and(w -> w.isNull(CmsBanner::getEndAt).or().ge(CmsBanner::getEndAt, now))
                .orderByAsc(CmsBanner::getSortNo)
                .orderByDesc(CmsBanner::getId));
        return rows.stream().map(this::toBanner).toList();
    }

    public PageResult<ContentDtos.NewsListItem> pageNews(Integer channelCode, PageQuery query) {
        Page<CmsNews> page = newsMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<CmsNews>()
                        .eq(CmsNews::getPublishStatus, PUBLISHED)
                        .le(CmsNews::getPublishAt, LocalDateTime.now())
                        .eq(channelCode != null, CmsNews::getChannelCode, channelCode)
                        .orderByDesc(CmsNews::getPublishAt)
                        .orderByDesc(CmsNews::getId));
        Page<ContentDtos.NewsListItem> mapped = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        mapped.setRecords(page.getRecords().stream().map(this::toNewsListItem).toList());
        return PageResult.of(mapped);
    }

    public ContentDtos.NewsDetail getNewsDetail(Long newsId, Long userIdOrNull) {
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
                .images(community.articleImages(newsId)).videos(community.articleVideos(newsId))
                .publishAt(news.getPublishAt())
                .likeCount(news.getLikeCount())
                .collectCount(news.getCollectCount())
                .liked(liked)
                .collected(collected)
                .author(community.newsAuthor(userIdOrNull, newsId))
                .commentCount(community.commentCount(newsId))
                .build();
    }

    @Transactional
    public ContentDtos.NewsActionResult updateNewsAction(Long userId, Long newsId, ContentDtos.NewsActionRequest request) {
        if (request.getLiked() == null && request.getCollected() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "liked/collected 至少传一个");
        }
        // Serialize all actions on an article before reading either counters or user flags.
        // Otherwise concurrent users overwrite each other's counts and first-like inserts race.
        CmsNews news = newsMapper.selectForAction(newsId);
        if (news == null || news.getPublishStatus() == null || news.getPublishStatus() != PUBLISHED ||
                (news.getPublishAt() != null && news.getPublishAt().isAfter(LocalDateTime.now()))) {
            throw new BizException(ErrorCode.NOT_FOUND, "资讯不存在");
        }
        NewsUserAction action = findAction(userId, newsId);
        if (action == null) {
            action = new NewsUserAction();
            action.setUserId(userId);
            action.setNewsId(newsId);
            action.setIsLiked(0);
            action.setIsCollected(0);
            newsUserActionMapper.insert(action);
        }

        int likeDelta = 0;
        int collectDelta = 0;
        if (request.getLiked() != null) {
            int next = request.getLiked() ? 1 : 0;
            likeDelta = next - nullToZero(action.getIsLiked());
            action.setIsLiked(next);
        }
        if (request.getCollected() != null) {
            int next = request.getCollected() ? 1 : 0;
            collectDelta = next - nullToZero(action.getIsCollected());
            action.setIsCollected(next);
        }
        newsUserActionMapper.updateById(action);

        if (likeDelta != 0 || collectDelta != 0) {
            news.setLikeCount(Math.max(0, nullToZero(news.getLikeCount()) + likeDelta));
            news.setCollectCount(Math.max(0, nullToZero(news.getCollectCount()) + collectDelta));
            newsMapper.updateById(news);
        }
        if(likeDelta!=0) community.notifyAction(userId,newsId,"LIKE",likeDelta>0);
        if(collectDelta!=0) community.notifyAction(userId,newsId,"COLLECT",collectDelta>0);

        return ContentDtos.NewsActionResult.builder()
                .liked(action.getIsLiked() == 1)
                .collected(action.getIsCollected() == 1)
                .likeCount(news.getLikeCount())
                .collectCount(news.getCollectCount())
                .build();
    }

    private CmsNews requirePublishedNews(Long newsId) {
        CmsNews news = newsMapper.selectById(newsId);
        if (news == null || news.getPublishStatus() == null || news.getPublishStatus() != PUBLISHED ||
                (news.getPublishAt() != null && news.getPublishAt().isAfter(LocalDateTime.now()))) {
            throw new BizException(ErrorCode.NOT_FOUND, "资讯不存在");
        }
        return news;
    }

    private NewsUserAction findAction(Long userId, Long newsId) {
        return newsUserActionMapper.selectOne(new LambdaQueryWrapper<NewsUserAction>()
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
                .build();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
