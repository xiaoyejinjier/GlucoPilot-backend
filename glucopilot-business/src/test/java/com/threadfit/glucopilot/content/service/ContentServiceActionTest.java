package com.threadfit.glucopilot.content.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.reaction.entity.NewsUserAction;
import com.threadfit.glucopilot.community.reaction.mapper.NewsUserActionMapper;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsBannerMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentServiceActionTest {
    @Mock private com.threadfit.glucopilot.community.shared.service.CommunityService community;

    @Mock private CmsBannerMapper bannerMapper;
    @Mock private CmsNewsMapper newsMapper;
    @Mock private NewsUserActionMapper newsUserActionMapper;

    @InjectMocks
    private com.threadfit.glucopilot.community.reaction.service.CommunityArticleActionService
            contentService;

    @Test
    void likeIncrementsCount() {
        CmsNews news = new CmsNews();
        news.setId(1L);
        news.setPublishStatus(1);
        news.setLikeCount(0);
        news.setCollectCount(0);
        when(newsMapper.selectForAction(1L)).thenReturn(news);
        when(newsUserActionMapper.selectOne(any())).thenReturn(null);
        when(newsUserActionMapper.insert(any(NewsUserAction.class)))
                .thenAnswer(
                        inv -> {
                            NewsUserAction a = inv.getArgument(0);
                            a.setId(10L);
                            return 1;
                        });

        com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionRequest req =
                new com.threadfit.glucopilot.community.reaction.dto.ReactionDtos
                        .NewsActionRequest();
        req.setLiked(true);
        com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionResult result =
                contentService.updateNewsAction(7L, 1L, req);

        assertTrue(result.getLiked());
        assertEquals(1, result.getLikeCount());
    }

    @Test
    void unpublishedNewsNotFound() {
        CmsNews news = new CmsNews();
        news.setId(2L);
        news.setPublishStatus(0);
        when(newsMapper.selectForAction(2L)).thenReturn(news);

        com.threadfit.glucopilot.community.reaction.dto.ReactionDtos.NewsActionRequest req =
                new com.threadfit.glucopilot.community.reaction.dto.ReactionDtos
                        .NewsActionRequest();
        req.setLiked(true);
        assertThrows(BizException.class, () -> contentService.updateNewsAction(1L, 2L, req));
    }
}
