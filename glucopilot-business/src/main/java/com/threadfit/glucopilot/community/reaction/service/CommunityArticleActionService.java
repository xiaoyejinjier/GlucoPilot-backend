package com.threadfit.glucopilot.community.reaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.reaction.entity.NewsUserAction;
import com.threadfit.glucopilot.community.reaction.mapper.NewsUserActionMapper;
import com.threadfit.glucopilot.community.shared.service.CommunityService;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 点赞/收藏写入：锁定文章，计算状态差值，更新计数与互动通知，均在同一事务内。 */
@Service
@RequiredArgsConstructor
public class CommunityArticleActionService {
    private static final int PUBLISHED = 1;
    private final CmsNewsMapper newsMapper;
    private final NewsUserActionMapper newsUserActionMapper;
    private final CommunityService community;

    @Transactional
    public com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionResult
            updateNewsAction(
                    Long userId,
                    Long newsId,
                    com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionRequest
                            request) {
        if (request.getLiked() == null && request.getCollected() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "liked/collected 至少传一个");
        }
        // Serialize all actions on an article before reading either counters or user flags.
        // Otherwise concurrent users overwrite each other's counts and first-like inserts race.
        CmsNews news = newsMapper.selectForAction(newsId);
        community.requireReadable(userId, newsId);
        if (news == null
                || news.getPublishStatus() == null
                || news.getPublishStatus() != PUBLISHED
                || (news.getPublishAt() != null
                        && news.getPublishAt().isAfter(LocalDateTime.now()))) {
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
        if (likeDelta != 0) community.notifyAction(userId, newsId, "LIKE", likeDelta > 0);
        if (collectDelta != 0) community.notifyAction(userId, newsId, "COLLECT", collectDelta > 0);

        return com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionResult
                .builder()
                .liked(action.getIsLiked() == 1)
                .collected(action.getIsCollected() == 1)
                .likeCount(news.getLikeCount())
                .collectCount(news.getCollectCount())
                .build();
    }

    private NewsUserAction findAction(Long userId, Long newsId) {
        return newsUserActionMapper.selectOne(
                new LambdaQueryWrapper<NewsUserAction>()
                        .eq(NewsUserAction::getUserId, userId)
                        .eq(NewsUserAction::getNewsId, newsId)
                        .last("LIMIT 1"));
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
