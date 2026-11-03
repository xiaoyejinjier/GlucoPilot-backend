package com.threadfit.glucopilot.community.shared.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.community.access.mapper.ArticleAccessMapper;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.article.mapper.CommunityPublicationMapper;
import com.threadfit.glucopilot.community.comment.controller.CommunityCommentController;
import com.threadfit.glucopilot.community.comment.mapper.CommunityCommentMapper;
import com.threadfit.glucopilot.community.comment.service.CommunityCommentService;
import com.threadfit.glucopilot.community.draft.mapper.CommunityDraftMapper;
import com.threadfit.glucopilot.community.follow.controller.CommunityFollowController;
import com.threadfit.glucopilot.community.follow.mapper.CommunityFollowMapper;
import com.threadfit.glucopilot.community.follow.service.CommunityFollowService;
import com.threadfit.glucopilot.community.media.mapper.CommunityArticleImageMapper;
import com.threadfit.glucopilot.community.media.mapper.CommunityArticlePresentationMapper;
import com.threadfit.glucopilot.community.media.service.CommunityArticlePresentationService;
import com.threadfit.glucopilot.community.message.controller.CommunityMessageController;
import com.threadfit.glucopilot.community.message.mapper.AdminMessageMapper;
import com.threadfit.glucopilot.community.message.mapper.CommunityMessageMapper;
import com.threadfit.glucopilot.community.message.service.CommunityMessageService;
import com.threadfit.glucopilot.community.moderation.mapper.CommunityModerationMapper;
import com.threadfit.glucopilot.community.notification.mapper.CommunityEventMapper;
import com.threadfit.glucopilot.community.notification.mapper.CommunityNotificationMapper;
import com.threadfit.glucopilot.community.notification.service.CommunityEventService;
import com.threadfit.glucopilot.community.profile.controller.CommunityProfileController;
import com.threadfit.glucopilot.community.profile.mapper.CommunityProfileMapper;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;
import com.threadfit.glucopilot.community.reaction.controller.CommunityReactionController;
import com.threadfit.glucopilot.community.reaction.mapper.CommunityReactionMapper;
import com.threadfit.glucopilot.community.reaction.service.CommunityReactionService;
import com.threadfit.glucopilot.community.recommendation.mapper.AdminRecommendationMapper;
import com.threadfit.glucopilot.community.recommendation.mapper.CommunityRecommendationMapper;
import com.threadfit.glucopilot.community.recommendation.service.CommunityRecommendationService;
import com.threadfit.glucopilot.community.report.mapper.CommunityReportMapper;
import com.threadfit.glucopilot.community.shared.service.CommunityService;
import com.threadfit.glucopilot.community.tag.mapper.CommunityTagMapper;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

/** Real MyBatis mappers against each test's isolated database, sharing its Spring transaction. */
public final class CommunityTestMappers {
    private CommunityTestMappers() {}

    private static final Map<DataSource, SqlSessionTemplate> SESSIONS = new LinkedHashMap<>();

    public static synchronized <T> T mapper(JdbcTemplate fixture, Class<T> type) {
        DataSource dataSource = Objects.requireNonNull(fixture.getDataSource());
        SqlSessionTemplate session = SESSIONS.get(dataSource);
        if (session == null) {
            try {
                var configuration = new MybatisConfiguration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
                for (Class<?> mapper :
                        new Class<?>[] {
                            ArticleAccessMapper.class,
                            com.threadfit.glucopilot.community.shared.mapper
                                    .CommunityAccountCleanupMapper.class,
                            com.threadfit.glucopilot.content.mapper.CmsNewsMapper.class,
                            CommunityArticleImageMapper.class,
                            CommunityDraftMapper.class,
                            CommunityModerationMapper.class,
                            CommunityNotificationMapper.class,
                            CommunityPublicationMapper.class,
                            CommunityReportMapper.class,
                            CommunityProfileMapper.class,
                            CommunityFollowMapper.class,
                            CommunityCommentMapper.class,
                            CommunityMessageMapper.class,
                            CommunityReactionMapper.class,
                            CommunityArticlePresentationMapper.class,
                            CommunityEventMapper.class,
                            CommunityTagMapper.class,
                            AdminMessageMapper.class,
                            CommunityRecommendationMapper.class,
                            AdminRecommendationMapper.class,
                            com.threadfit.glucopilot.community.comment.mapper
                                    .AdminCommunityCommentMapper.class,
                            com.threadfit.glucopilot.community.report.mapper
                                    .CommunityReportManagementMapper.class
                        }) configuration.addMapper(mapper);
                var factory = new MybatisSqlSessionFactoryBean();
                factory.setDataSource(dataSource);
                factory.setConfiguration(configuration);
                session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
                if (SESSIONS.size() >= 4) SESSIONS.remove(SESSIONS.keySet().iterator().next());
                SESSIONS.put(dataSource, session);
            } catch (Exception error) {
                throw new IllegalStateException(
                        "Cannot create community MyBatis test mappers", error);
            }
        }
        return session.getMapper(type);
    }

    public static ArticleAccessService access(JdbcTemplate fixture) {
        return new ArticleAccessService(mapper(fixture, ArticleAccessMapper.class));
    }

    public static CommunityRecommendationService recommendation(JdbcTemplate fixture) {
        return new CommunityRecommendationService(
                mapper(fixture, CommunityRecommendationMapper.class));
    }

    /** Assemble actual feature services sharing the fixture's MyBatis session and transaction. */
    public static CommunityService community(JdbcTemplate fixture) {
        var access = access(fixture);
        var profiles =
                new CommunityProfileService(
                        mapper(fixture, CommunityProfileMapper.class),
                        mapper(fixture, CommunityFollowMapper.class));
        var events = new CommunityEventService(mapper(fixture, CommunityEventMapper.class));
        var follows =
                new CommunityFollowService(
                        mapper(fixture, CommunityFollowMapper.class), profiles, events);
        var reactions =
                new CommunityReactionService(
                        mapper(fixture, CommunityReactionMapper.class), profiles, access);
        var comments =
                new CommunityCommentService(
                        mapper(fixture, CommunityCommentMapper.class), profiles, access, events);
        var messages =
                new CommunityMessageService(
                        mapper(fixture, CommunityMessageMapper.class), profiles);
        var presentation =
                new CommunityArticlePresentationService(
                        mapper(fixture, CommunityArticlePresentationMapper.class));
        return new CommunityService(
                profiles,
                follows,
                reactions,
                comments,
                messages,
                presentation,
                events,
                access,
                new com.threadfit.glucopilot.community.shared.service
                        .CommunityAccountCleanupService(
                        mapper(
                                fixture,
                                com.threadfit.glucopilot.community.shared.mapper
                                        .CommunityAccountCleanupMapper.class)));
    }

    public static Object[] controllers(CommunityService service) {
        return new Object[] {
            new CommunityProfileController(service.profileService()),
            new CommunityFollowController(service.followService()),
            new CommunityReactionController(service.reactionService()),
            new CommunityCommentController(service.commentService()),
            new CommunityMessageController(service.messageService())
        };
    }

    public static com.threadfit.glucopilot.community.report.service.CommunityReportService reports(
            JdbcTemplate fixture) {
        return new com.threadfit.glucopilot.community.report.service.CommunityReportService(
                mapper(
                        fixture,
                        com.threadfit.glucopilot.community.report.mapper
                                .CommunityReportManagementMapper.class),
                mapper(fixture, com.threadfit.glucopilot.content.mapper.CmsNewsMapper.class),
                access(fixture));
    }

    public static com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService
            adminComments(JdbcTemplate fixture) {
        return new com.threadfit.glucopilot.community.comment.service.AdminCommunityCommentService(
                mapper(
                        fixture,
                        com.threadfit.glucopilot.community.comment.mapper
                                .AdminCommunityCommentMapper.class));
    }
}
