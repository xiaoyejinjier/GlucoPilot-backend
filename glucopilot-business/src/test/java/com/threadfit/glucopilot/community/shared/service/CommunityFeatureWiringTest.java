package com.threadfit.glucopilot.community.shared.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.community.access.mapper.ArticleAccessMapper;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.article.mapper.CommunityPublicationMapper;
import com.threadfit.glucopilot.community.article.service.CommunityPublicationService;
import com.threadfit.glucopilot.community.comment.mapper.AdminCommunityCommentMapper;
import com.threadfit.glucopilot.community.comment.mapper.CommunityCommentMapper;
import com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService;
import com.threadfit.glucopilot.community.comment.service.CommunityCommentService;
import com.threadfit.glucopilot.community.follow.mapper.CommunityFollowMapper;
import com.threadfit.glucopilot.community.follow.service.CommunityFollowService;
import com.threadfit.glucopilot.community.media.mapper.CommunityArticlePresentationMapper;
import com.threadfit.glucopilot.community.media.service.CommunityArticlePresentationService;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.MessageRequest;
import com.threadfit.glucopilot.community.message.entity.CommunityMessage;
import com.threadfit.glucopilot.community.message.mapper.CommunityMessageMapper;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;
import com.threadfit.glucopilot.community.notification.mapper.CommunityEventMapper;
import com.threadfit.glucopilot.community.notification.service.CommunityEventService;
import com.threadfit.glucopilot.community.profile.mapper.CommunityProfileMapper;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;
import com.threadfit.glucopilot.community.reaction.mapper.CommunityReactionMapper;
import com.threadfit.glucopilot.community.reaction.mapper.NewsUserActionMapper;
import com.threadfit.glucopilot.community.reaction.service.CommunityArticleActionService;
import com.threadfit.glucopilot.community.reaction.service.CommunityReactionService;
import com.threadfit.glucopilot.community.recommendation.mapper.CommunityRecommendationMapper;
import com.threadfit.glucopilot.community.recommendation.service.CommunityRecommendationService;
import com.threadfit.glucopilot.community.report.mapper.CommunityReportManagementMapper;
import com.threadfit.glucopilot.community.report.service.CommunityReportService;
import com.threadfit.glucopilot.community.shared.mapper.CommunityAccountCleanupMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Cross-feature injection and real Spring transaction proxies after the package split. */
class CommunityFeatureWiringTest {
    @Configuration
    @EnableTransactionManagement
    static class Transactions {
        @Bean
        DataSourceTransactionManager transactionManager() {
            return new DataSourceTransactionManager(
                    new DriverManagerDataSource("jdbc:h2:mem:community_feature_wiring", "sa", ""));
        }
    }

    @Test
    void featuresWireWithoutCyclesAndMessageInsertRunsInTransaction() {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (var type :
                    new Class<?>[] {
                        ArticleAccessMapper.class, CommunityPublicationMapper.class,
                        AdminCommunityCommentMapper.class, CommunityCommentMapper.class,
                        CommunityFollowMapper.class, CommunityArticlePresentationMapper.class,
                        CommunityMessageMapper.class, CommunityEventMapper.class,
                        CommunityProfileMapper.class, CommunityReactionMapper.class,
                        NewsUserActionMapper.class, CommunityRecommendationMapper.class,
                        CommunityReportManagementMapper.class, CmsNewsMapper.class,
                        CommunityAccountCleanupMapper.class
                    }) registerMock(context, type);
            context.register(
                    Transactions.class,
                    ArticleAccessService.class,
                    CommunityPublicationService.class,
                    AdminCommunityCommentService.class,
                    CommunityCommentService.class,
                    CommunityFollowService.class,
                    CommunityArticlePresentationService.class,
                    CommunityMessageService.class,
                    CommunityEventService.class,
                    CommunityProfileService.class,
                    CommunityArticleActionService.class,
                    CommunityReactionService.class,
                    CommunityRecommendationService.class,
                    CommunityReportService.class,
                    CommunityAccountCleanupService.class,
                    CommunityService.class);
            context.refresh();

            assertNotNull(context.getBean(CommunityPublicationService.class));
            assertNotNull(context.getBean(CommunityArticleActionService.class));
            var mapper = context.getBean(CommunityMessageMapper.class);
            when(mapper.lockMessageParticipants(1, 2)).thenReturn(List.of(1L, 2L));
            var row = new CommunityMessage();
            row.setId(10L);
            row.setSenderId(1L);
            row.setRecipientId(2L);
            row.setContent("你好");
            when(mapper.selectSavedMessage(1, "feature-wire-01")).thenReturn(row);
            when(mapper.insertMessage(1, 2, "你好", "feature-wire-01"))
                    .thenAnswer(
                            invocation -> {
                                assertTrue(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return 1;
                            });
            var result =
                    context.getBean(CommunityMessageService.class)
                            .send(1, 2, new MessageRequest("你好", "feature-wire-01"));
            assertEquals("10", result.id());
            verify(mapper).insertMessage(1, 2, "你好", "feature-wire-01");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

            var cleanup = context.getBean(CommunityAccountCleanupMapper.class);
            when(cleanup.deleteFollows(1))
                    .thenAnswer(
                            invocation -> {
                                assertTrue(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return 1;
                            });
            context.getBean(CommunityService.class).purgeAccountData(1);
            verify(cleanup).anonymizeBlogger(1);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    private static <T> void registerMock(
            AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
