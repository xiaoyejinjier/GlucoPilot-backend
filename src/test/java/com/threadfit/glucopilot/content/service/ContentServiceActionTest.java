package com.threadfit.glucopilot.content.service;

import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.entity.NewsUserAction;
import com.threadfit.glucopilot.content.mapper.CmsBannerMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import com.threadfit.glucopilot.content.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.content.mapper.FoodNutritionMapper;
import com.threadfit.glucopilot.content.mapper.NewsUserActionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentServiceActionTest {

    @Mock
    private CmsBannerMapper bannerMapper;
    @Mock
    private CmsNewsMapper newsMapper;
    @Mock
    private FoodCategoryMapper foodCategoryMapper;
    @Mock
    private FoodNutritionMapper foodNutritionMapper;
    @Mock
    private NewsUserActionMapper newsUserActionMapper;

    @InjectMocks
    private ContentService contentService;

    @Test
    void likeIncrementsCount() {
        CmsNews news = new CmsNews();
        news.setId(1L);
        news.setPublishStatus(1);
        news.setLikeCount(0);
        news.setCollectCount(0);
        when(newsMapper.selectById(1L)).thenReturn(news);
        when(newsUserActionMapper.selectOne(any())).thenReturn(null);
        when(newsUserActionMapper.insert(any(NewsUserAction.class))).thenAnswer(inv -> {
            NewsUserAction a = inv.getArgument(0);
            a.setId(10L);
            return 1;
        });

        ContentDtos.NewsActionRequest req = new ContentDtos.NewsActionRequest();
        req.setLiked(true);
        ContentDtos.NewsActionResult result = contentService.updateNewsAction(7L, 1L, req);

        assertTrue(result.getLiked());
        assertEquals(1, result.getLikeCount());
    }

    @Test
    void unpublishedNewsNotFound() {
        CmsNews news = new CmsNews();
        news.setId(2L);
        news.setPublishStatus(0);
        when(newsMapper.selectById(2L)).thenReturn(news);

        ContentDtos.NewsActionRequest req = new ContentDtos.NewsActionRequest();
        req.setLiked(true);
        assertThrows(BizException.class, () -> contentService.updateNewsAction(1L, 2L, req));
    }
}
