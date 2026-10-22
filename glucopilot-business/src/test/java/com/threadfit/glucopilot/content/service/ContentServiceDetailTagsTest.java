package com.threadfit.glucopilot.content.service;

import com.threadfit.glucopilot.community.CommunityService;
import com.threadfit.glucopilot.community.PublicationDtos;
import com.threadfit.glucopilot.content.dto.ContentDtos;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsBannerMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import com.threadfit.glucopilot.content.mapper.NewsUserActionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentServiceDetailTagsTest {
    @Mock private CommunityService community;
    @Mock private CmsBannerMapper bannerMapper;
    @Mock private CmsNewsMapper newsMapper;
    @Mock private NewsUserActionMapper newsUserActionMapper;
    @InjectMocks private ContentService contentService;

    @Test
    void newsDetailIncludesCommunityTags() {
        CmsNews news = new CmsNews();
        news.setId(9L);
        news.setTitle("控糖日常");
        news.setSummaryText("摘要");
        news.setChannelCode(4);
        news.setContentType(1);
        news.setContentBody("正文");
        news.setPublishStatus(1);
        news.setPublishAt(LocalDateTime.now().minusHours(1));
        news.setLikeCount(0);
        news.setCollectCount(0);
        when(newsMapper.selectById(9L)).thenReturn(news);
        when(community.articleImages(9L)).thenReturn(List.of());
        when(community.articleVideos(9L)).thenReturn(List.of());
        when(community.articleTags(9L)).thenReturn(List.of(new PublicationDtos.Tag("diet", "饮食控糖")));
        when(community.newsAuthor(null, 9L)).thenReturn(null);
        when(community.commentCount(9L)).thenReturn(0L);

        ContentDtos.NewsDetail detail = contentService.getNewsDetail(9L, null);

        assertEquals(1, detail.getTags().size());
        assertEquals("diet", detail.getTags().getFirst().code());
        assertEquals("饮食控糖", detail.getTags().getFirst().name());
    }
}
