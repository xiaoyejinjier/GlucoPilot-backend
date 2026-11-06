package com.threadfit.glucopilot.community.article.service;

import static com.threadfit.glucopilot.community.article.dto.PublicationDtos.*;
import static com.threadfit.glucopilot.community.report.dto.ReportDtos.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.access.dto.ArticleAccessDtos;
import com.threadfit.glucopilot.community.access.mapper.ArticleAccessMapper;
import com.threadfit.glucopilot.community.access.service.ArticleAccessService;
import com.threadfit.glucopilot.community.article.mapper.CommunityPublicationMapper;
import com.threadfit.glucopilot.community.comment.dto.CommentDtos;
import com.threadfit.glucopilot.community.draft.controller.CommunityDraftController;
import com.threadfit.glucopilot.community.draft.dto.CommunityDraftDtos;
import com.threadfit.glucopilot.community.draft.mapper.CommunityDraftMapper;
import com.threadfit.glucopilot.community.draft.service.CommunityDraftService;
import com.threadfit.glucopilot.community.media.mapper.CommunityArticleImageMapper;
import com.threadfit.glucopilot.community.media.service.CommunityArticleImageService;
import com.threadfit.glucopilot.community.moderation.mapper.CommunityModerationMapper;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict;
import com.threadfit.glucopilot.community.notification.mapper.CommunityNotificationMapper;
import com.threadfit.glucopilot.community.notification.service.CommunityNotificationService;
import com.threadfit.glucopilot.community.recommendation.mapper.AdminRecommendationMapper;
import com.threadfit.glucopilot.community.recommendation.service.AdminRecommendationService;
import com.threadfit.glucopilot.community.report.controller.CommunityReportFeedbackController;
import com.threadfit.glucopilot.community.report.mapper.CommunityReportMapper;
import com.threadfit.glucopilot.community.report.service.CommunityReportFeedbackService;
import com.threadfit.glucopilot.community.shared.support.CommunityTestMappers;
import com.threadfit.glucopilot.community.shared.support.CommunityTestSchema;
import com.threadfit.glucopilot.community.tag.dto.CommunityTagDtos;
import com.threadfit.glucopilot.community.tag.mapper.CommunityTagMapper;
import com.threadfit.glucopilot.community.tag.service.CommunityTagService;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;

class AdminCommunityArticlesTest {
    private JdbcTemplate jdbc;
    private CommunityPublicationService service;
    private Connection migrationConnection;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:admin_articles_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        migrationConnection = ds.getConnection();
        String schema =
                new ClassPathResource("db/migration/V1__init_schema.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        for (String table : List.of("cms_news", "news_user_action", "media_file")) {
            int start = schema.indexOf("CREATE TABLE " + table + " (");
            String ddl =
                    schema.substring(start, schema.indexOf(";", start))
                            .replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY");
            try (var statement = migrationConnection.createStatement()) {
                statement.execute(ddl);
            }
        }
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT DEFAULT"
                        + " 1,is_deleted INT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname"
                        + " VARCHAR(64),avatar_url VARCHAR(512),is_deleted INT DEFAULT 0)");
        for (String file :
                List.of(
                        "V29__community_interactions.sql",
                        "V31__community_publication_moderation.sql",
                        "V32__community_comment_replies.sql",
                        "V33__community_article_images.sql")) {
            var resource = new ClassPathResource("db/migration/" + file);
            var migration =
                    new ResourceDatabasePopulator(
                            new org.springframework.core.io.ByteArrayResource(
                                    resource.getContentAsString(StandardCharsets.UTF_8)
                                            .replace(
                                                    "DROP CHECK chk_mf_biz",
                                                    "DROP CONSTRAINT chk_mf_biz")
                                            .getBytes(StandardCharsets.UTF_8)));
            migration.setSqlScriptEncoding("UTF-8");
            migration.populate(migrationConnection);
        }
        CommunityTestSchema.applyDraftMigration(migrationConnection);
        CommunityTestSchema.applyModerationMigration(migrationConnection);
        jdbc.update("INSERT INTO user_account(id) VALUES(2)");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(2,'用户小明')");
        article(10, "官方已发布", 0, 1);
        article(11, "官方草稿", 0, 0);
        article(12, "官方下架", 0, 2);
        article(20, "用户等待审核", 1, 0);
        article(21, "用户已发布", 1, 1);
        article(22, "已删除文章", 1, 1);
        jdbc.update(
                "UPDATE cms_news SET is_deleted=1,deleted_at=CURRENT_TIMESTAMP,deleted_by=99 WHERE"
                        + " id=22");
        jdbc.update(
                "INSERT INTO community_article(news_id,author_user_id,state,ai_verdict)"
                    + " VALUES(20,2,'PENDING_ADMIN','PASS'),(21,2,'PUBLISHED','PASS'),(22,2,'PUBLISHED','PASS')");
        service =
                new CommunityPublicationService(
                        CommunityTestMappers.mapper(jdbc, CommunityPublicationMapper.class),
                        mock(CmsNewsMapper.class),
                        CommunityTestMappers.community(jdbc),
                        CommunityTestMappers.access(jdbc),
                        CommunityTestMappers.recommendation(jdbc),
                        CommunityTestMappers.reports(jdbc),
                        CommunityTestMappers.adminComments(jdbc));
    }

    // H2 rounds DATETIME to seconds; seed already-published fixtures before the current instant.
    private void article(long id, String title, int managed, int status) {
        jdbc.update(
                "INSERT INTO"
                    + " cms_news(id,title,summary_text,content_body,channel_code,publish_at,publish_status,community_managed)"
                    + " VALUES(?,?,?,'完整正文',4,DATEADD('SECOND',-2,CURRENT_TIMESTAMP),?,?)",
                id,
                title,
                "文章摘要",
                status,
                managed);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            jdbc.execute("DROP ALL OBJECTS");
        } finally {
            migrationConnection.close();
        }
    }

    private PageResult<Article> list(String state, String source, String keyword) {
        return service.adminArticles(state, source, keyword, new PageQuery());
    }

    private CommunityDraftService drafts() {
        return new CommunityDraftService(
                CommunityTestMappers.mapper(jdbc, CommunityDraftMapper.class),
                new com.threadfit.glucopilot.common.util.JsonUtils(
                        new tools.jackson.databind.ObjectMapper()),
                service,
                CommunityTestMappers.access(jdbc));
    }

    private void recommendationTag(long articleId, String code) {
        jdbc.update(
                "INSERT INTO community_article_tag(news_id,tag_id) SELECT ?,id FROM community_tag"
                        + " WHERE code=?",
                articleId,
                code);
    }

    private void viewed(long viewer, long articleId, int daysAgo) {
        jdbc.update(
                "INSERT INTO community_article_view(news_id,user_id,view_date,created_at)"
                        + " VALUES(?,?,?,?)",
                articleId,
                viewer,
                java.time.LocalDate.now().minusDays(daysAgo),
                java.time.LocalDateTime.now().minusDays(daysAgo).minusMinutes(2));
    }

    private List<String> recommended(long viewer) {
        var q = new PageQuery();
        q.setPageSize(50);
        return service.feed(viewer, null, false, null, q).getRecords().stream()
                .map(Article::id)
                .toList();
    }

    private AdminRecommendationService recommendationAdmin() {
        return new AdminRecommendationService(
                CommunityTestMappers.recommendation(jdbc),
                CommunityTestMappers.mapper(jdbc, CommunityPublicationMapper.class),
                CommunityTestMappers.mapper(jdbc, AdminRecommendationMapper.class));
    }

    @Test
    void adminRecommendationScoresMatchFeedOrderAndEveryContributionAddsUp() {
        recommendationTag(10, "diet");
        recommendationTag(21, "diet");
        recommendationTag(21, "exercise");
        viewed(2, 10, 0);
        var report = recommendationAdmin().report(2);
        assertFalse(report.coldStart());
        assertTrue(report.vectorNorm() > 0);
        assertEquals(recommended(2), report.articles().stream().map(a -> a.id()).toList());
        assertEquals(
                service.feed(2, null, false, null, new PageQuery()).getTotal(),
                report.candidateCount());
        for (var item : report.articles()) {
            assertEquals(
                    item.similarity(),
                    item.tags().stream().mapToDouble(t -> t.contribution()).sum(),
                    1e-9);
            assertEquals(
                    item.totalScore(),
                    item.interestScore()
                            + item.popularityScore()
                            + item.freshnessScore()
                            + item.followingScore(),
                    1e-8);
            assertTrue(item.totalScore() >= 0 && item.totalScore() <= 100.000001);
        }
        assertEquals(
                1.0,
                report.vector().stream()
                        .mapToDouble(d -> d.normalizedWeight() * d.normalizedWeight())
                        .sum(),
                1e-9);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_article_view WHERE user_id=2",
                        Integer.class));
    }

    @Test
    void adminRecommendationReturnsAtMostTwentyAndKeepsViewerAccessAndColdStart() {
        peer(3);
        for (long id = 100; id < 125; id++) article(id, "候选文章" + id, 0, 1);
        jdbc.update("INSERT INTO community_article_hidden(user_id,news_id) VALUES(3,124)");
        jdbc.update(
                "INSERT INTO community_article_access(news_id,visibility) VALUES(123,'PRIVATE')");
        var report = recommendationAdmin().report(3);
        assertTrue(report.coldStart());
        assertTrue(report.vector().isEmpty());
        assertEquals(20, report.articles().size());
        assertFalse(
                report.articles().stream()
                        .anyMatch(a -> a.id().equals("124") || a.id().equals("123")));
        assertThrows(BizException.class, () -> recommendationAdmin().report(0));
        assertThrows(BizException.class, () -> recommendationAdmin().report(999));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=3");
        assertThrows(BizException.class, () -> recommendationAdmin().report(3));
    }

    @Test
    void readingDifferentTagsChangesRecommendationsForDifferentUsers() {
        peer(3);
        article(100, "饮食候选", 0, 1);
        article(101, "运动候选", 0, 1);
        recommendationTag(10, "diet");
        recommendationTag(21, "exercise");
        recommendationTag(100, "diet");
        recommendationTag(101, "exercise");
        viewed(2, 10, 0);
        viewed(3, 21, 0);
        var diet = recommended(2);
        var exercise = recommended(3);
        assertTrue(diet.indexOf("100") < diet.indexOf("101"));
        assertTrue(exercise.indexOf("101") < exercise.indexOf("100"));
    }

    @Test
    void currentLikesAndCollectionsLearnPreferencesAndRemovingThemRemovesTheSignal() {
        article(100, "饮食候选", 0, 1);
        article(101, "运动候选", 0, 1);
        recommendationTag(10, "diet");
        recommendationTag(100, "diet");
        recommendationTag(101, "exercise");
        jdbc.update(
                "INSERT INTO news_user_action(user_id,news_id,is_liked,is_collected,gmt_modified)"
                        + " VALUES(2,10,1,1,?)",
                java.time.LocalDateTime.now().minusMinutes(2));
        var profile = CommunityTestMappers.recommendation(jdbc).prepare(2);
        assertFalse(profile.tags().isEmpty());
        var ids = recommended(2);
        assertTrue(ids.indexOf("100") < ids.indexOf("101"));
        jdbc.update(
                "UPDATE news_user_action SET is_liked=0,is_collected=0 WHERE user_id=2 AND"
                        + " news_id=10");
        assertTrue(CommunityTestMappers.recommendation(jdbc).prepare(2).tags().isEmpty());
    }

    @Test
    void cosineNormalizesArticleTagCountAndDisabledTagsDoNotBuildInterests() {
        article(100, "单一饮食主题", 0, 1);
        article(101, "多个不同主题", 0, 1);
        recommendationTag(10, "diet");
        recommendationTag(100, "diet");
        recommendationTag(101, "diet");
        recommendationTag(101, "exercise");
        recommendationTag(101, "daily");
        viewed(2, 10, 0);
        var ids = recommended(2);
        assertTrue(ids.indexOf("100") < ids.indexOf("101"));
        jdbc.update("UPDATE community_tag SET enabled=0 WHERE code='diet'");
        assertTrue(CommunityTestMappers.recommendation(jdbc).prepare(2).tags().isEmpty());
    }

    @Test
    void coldStartUsesPopularityFreshnessAndFollowingWithoutDroppingUntaggedArticles() {
        peer(3);
        article(100, "普通旧文章", 0, 1);
        article(101, "热门旧文章", 0, 1);
        article(102, "新文章无标签", 0, 1);
        jdbc.update(
                "UPDATE cms_news SET publish_at=? WHERE id IN(100,101)",
                java.time.LocalDateTime.now().minusDays(40));
        jdbc.update("UPDATE cms_news SET like_count=100,collect_count=20 WHERE id=101");
        var ids = recommended(2);
        assertTrue(ids.indexOf("101") < ids.indexOf("100"));
        assertTrue(ids.indexOf("102") < ids.indexOf("100"));
        article(103, "关注作者的新文章", 1, 1);
        article(104, "普通作者的新文章", 0, 1);
        jdbc.update(
                "INSERT INTO community_article(news_id,author_user_id,state,ai_verdict)"
                        + " VALUES(103,3,'PUBLISHED','PASS')");
        jdbc.update(
                "INSERT INTO community_follow(follower_id,target_key,following) VALUES(2,'u3',1)");
        ids = recommended(2);
        assertTrue(ids.indexOf("103") < ids.indexOf("104"));
    }

    @Test
    void inaccessibleOrHiddenHistoryCannotInfluenceRecommendations() {
        peer(3);
        recommendationTag(10, "diet");
        recommendationTag(21, "exercise");
        recommendationTag(20, "daily");
        jdbc.update(
                "INSERT INTO community_article_access(news_id,visibility) VALUES(21,'PRIVATE')");
        jdbc.update("INSERT INTO community_article_hidden(user_id,news_id) VALUES(3,10)");
        viewed(3, 10, 0);
        viewed(3, 21, 0);
        viewed(3, 20, 0);
        assertTrue(CommunityTestMappers.recommendation(jdbc).prepare(3).tags().isEmpty());
        var ids = recommended(3);
        assertFalse(ids.contains("10"));
        assertFalse(ids.contains("21"));
        assertFalse(ids.contains("20"));
    }

    @Test
    void recommendationsRankBeforePaginationAndDoNotTruncateOldMatchingArticles() {
        recommendationTag(10, "diet");
        viewed(2, 10, 0);
        jdbc.update(
                "UPDATE cms_news SET publish_at=? WHERE id=10",
                java.time.LocalDateTime.now().minusDays(60));
        article(100, "较旧的匹配文章", 0, 1);
        recommendationTag(100, "diet");
        jdbc.update(
                "UPDATE cms_news SET publish_at=? WHERE id=100",
                java.time.LocalDateTime.now().minusDays(15));
        for (long id = 200; id < 260; id++) {
            article(id, "较新其他主题", 0, 1);
            recommendationTag(id, "exercise");
        }
        var q = new PageQuery();
        q.setPageSize(1);
        assertEquals("100", service.feed(2, null, false, null, q).getRecords().getFirst().id());
        q.setPageSize(10);
        var all = new HashSet<String>();
        long total = 0;
        for (int page = 1; page <= 7; page++) {
            q.setPageNum(page);
            var result = service.feed(2, null, false, null, q);
            total = result.getTotal();
            for (var item : result.getRecords()) assertTrue(all.add(item.id()));
        }
        assertEquals(total, all.size());
        assertTrue(all.contains("100"));
        assertTrue(all.contains("259"));
    }

    @Test
    void latestAndSearchPreserveChronologicalOrderAndInvalidSortIsRejected() {
        article(100, "推荐饮食", 0, 1);
        article(101, "推荐运动", 0, 1);
        recommendationTag(10, "diet");
        recommendationTag(100, "diet");
        recommendationTag(101, "exercise");
        viewed(2, 10, 0);
        var latest =
                service.feed(2, null, false, null, "LATEST", new PageQuery()).getRecords().stream()
                        .map(Article::id)
                        .toList();
        assertTrue(latest.indexOf("101") < latest.indexOf("100"));
        var search = service.feed(2, null, false, "推荐", "RECOMMEND", new PageQuery()).getRecords();
        assertEquals(List.of("101", "100"), search.stream().map(Article::id).toList());
        assertThrows(
                BizException.class,
                () -> service.feed(2, null, false, null, "invalid", new PageQuery()));
    }

    private CommunityReportFeedbackService feedback() {
        return new CommunityReportFeedbackService(
                CommunityTestMappers.mapper(jdbc, CommunityReportMapper.class));
    }

    private ArticleAccessService access() {
        return new ArticleAccessService(
                CommunityTestMappers.mapper(jdbc, ArticleAccessMapper.class));
    }

    private void peer(long id) {
        jdbc.update("INSERT INTO user_account(id) VALUES(?)", id);
        jdbc.update(
                "INSERT INTO user_profile(user_id,nickname,bio) VALUES(?,?,'认真记录生活')",
                id,
                "好友" + id);
    }

    private void mutual(long id) {
        jdbc.update(
                "INSERT INTO community_follow(follower_id,target_key,following)"
                        + " VALUES(2,?,1),(?,'u2',1)",
                "u" + id,
                id);
    }

    @Test
    void editorialArticlesAppearAfterRealFinalReviewWithNoSyntheticUserAccount() {
        article(30, "编辑部科普", 1, 0);
        jdbc.update(
                "INSERT INTO community_article(news_id,seed_key,state,ai_verdict)"
                        + " VALUES(30,'editorial-fixture','PENDING_ADMIN','PASS')");
        jdbc.update(
                "INSERT INTO community_article_tag(news_id,tag_id) SELECT 30,id FROM community_tag"
                        + " WHERE code='diet'");
        assertFalse(
                service.feed(2, null, false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("30")));
        assertThrows(BizException.class, () -> service.get(2L, 30, false));
        var published =
                tx().execute(s -> service.review(99, 30, new Review(1, "APPROVE", "已核对原文")));
        assertEquals("PUBLISHED", published.state());
        assertEquals("b1", published.author().key());
        assertNull(published.author().userId());
        assertTrue(
                service.feed(2, "diet", false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("30")));
        assertTrue(
                service.byAuthor(2, "b1", new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("30")));
        assertEquals("完整正文", service.get(2L, 30, false).body());
        access().requireReadable(null, 30);
        jdbc.update(
                "INSERT INTO community_follow(follower_id,target_key,following) VALUES(2,'b1',1)");
        assertTrue(
                service.feed(2, null, true, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("30")));
        jdbc.update("INSERT INTO news_user_action(user_id,news_id,is_collected) VALUES(2,30,1)");
        assertEquals(
                "30",
                CommunityTestMappers.community(jdbc)
                        .collections(2, new PageQuery())
                        .getRecords()
                        .getFirst()
                        .getId());
        jdbc.update(
                "UPDATE cms_news SET publish_at=DATEADD('DAY',1,CURRENT_TIMESTAMP) WHERE id=30");
        assertThrows(BizException.class, () -> access().requireReadable(2L, 30));
        jdbc.update("UPDATE cms_news SET publish_at=CURRENT_TIMESTAMP WHERE id=30");
        jdbc.update(
                "INSERT INTO community_article_access(news_id,visibility) VALUES(30,'PRIVATE')");
        assertThrows(BizException.class, () -> service.get(2L, 30, false));
        jdbc.update("DELETE FROM community_article_access WHERE news_id=30");
        access().hidden(2, 30, true);
        assertFalse(
                service.feed(2, null, false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("30")));
        assertEquals(
                0, CommunityTestMappers.community(jdbc).collections(2, new PageQuery()).getTotal());
    }

    @Test
    void editorialFallbackRequiresEnabledStandalonePublisherAndNeverRevivesDisabledUsers() {
        peer(3);
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=3");
        jdbc.update(
                "INSERT INTO community_blogger(id,user_id,nickname,enabled)"
                        + " VALUES(40,NULL,'停用编辑部',0),(41,3,'关联停用用户',1)");
        for (long id : List.of(30L, 31L, 32L, 33L, 34L)) article(id, "不可公开的发布身份", 1, 1);
        jdbc.update("UPDATE cms_news SET blogger_id=40 WHERE id=30");
        jdbc.update("UPDATE cms_news SET blogger_id=999 WHERE id=31");
        jdbc.update("UPDATE cms_news SET blogger_id=41 WHERE id=32");
        jdbc.update(
                "INSERT INTO community_article(news_id,author_user_id,state)"
                    + " VALUES(30,NULL,'PUBLISHED'),(31,NULL,'PUBLISHED'),(32,NULL,'PUBLISHED'),(33,999,'PUBLISHED'),(34,3,'PUBLISHED')");
        var ids =
                service.feed(2, null, false, null, new PageQuery()).getRecords().stream()
                        .map(Article::id)
                        .toList();
        for (long id : List.of(30L, 31L, 32L, 33L, 34L)) {
            assertFalse(ids.contains(Long.toString(id)));
            assertThrows(BizException.class, () -> service.get(2L, id, false));
        }
        assertTrue(ids.contains("21"));
    }

    @Test
    void privateArticlesAreFilteredAcrossDetailFeedsCollectionsCommentsAndLegacyNews() {
        peer(3);
        var policy =
                tx().execute(
                                s ->
                                        access().change(
                                                        2,
                                                        21,
                                                        new ArticleAccessDtos.Change(
                                                                "PRIVATE", List.of(), 0)));
        assertEquals("PRIVATE", policy.visibility());
        assertEquals(1, policy.revision());
        assertEquals("PRIVATE", service.get(2L, 21, false).visibility());
        assertThrows(BizException.class, () -> service.get(3L, 21, false));
        assertThrows(BizException.class, () -> service.get(null, 21, false));
        assertFalse(
                service.feed(3, null, false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("21")));
        assertFalse(
                service.byAuthor(3, "u2", new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("21")));
        jdbc.update("INSERT INTO news_user_action(user_id,news_id,is_collected) VALUES(3,21,1)");
        var community = CommunityTestMappers.community(jdbc);
        assertEquals(0, community.collections(3, new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> community.comments(3, 21, new PageQuery()));
        assertThrows(
                BizException.class,
                () ->
                        community.addComment(
                                3,
                                21,
                                new CommentDtos.CommentRequest(
                                        "不能读取的文章也不能评论", "private-comment-01")));
        var content =
                new com.threadfit.glucopilot.content.service.ContentService(
                        mock(com.threadfit.glucopilot.content.mapper.CmsBannerMapper.class),
                        mock(CmsNewsMapper.class),
                        mock(
                                com.threadfit.glucopilot.community.reaction.mapper
                                        .NewsUserActionMapper.class),
                        community,
                        mock(
                                com.threadfit.glucopilot.community.reaction.service
                                        .CommunityArticleActionService.class));
        assertThrows(BizException.class, () -> content.getNewsDetail(21L, 3L));
        assertEquals("用户已发布", service.get(null, 21, true).title());
    }

    @Test
    void selectedFriendsMustBeMutualAndPermissionIsRevokedWhenEitherSideUnfollows() {
        peer(3);
        peer(4);
        mutual(3);
        mutual(4);
        assertEquals(2, access().friends(2, null, new PageQuery()).getTotal());
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                21,
                                                new ArticleAccessDtos.Change(
                                                        "FRIENDS", List.of("3"), 0)));
        assertEquals("FRIENDS", service.get(3L, 21, false).visibility());
        assertEquals(List.of(), service.get(3L, 21, false).viewerIds());
        assertEquals(List.of("3"), service.get(2L, 21, false).viewerIds());
        assertThrows(BizException.class, () -> service.get(4L, 21, false));
        assertThrows(
                BizException.class,
                () -> access().change(4, 21, new ArticleAccessDtos.Change("PUBLIC", List.of(), 1)));
        jdbc.update(
                "UPDATE community_follow SET following=0 WHERE follower_id=3 AND target_key='u2'");
        assertThrows(BizException.class, () -> service.get(3L, 21, false));
        assertEquals(1, access().friends(2, null, new PageQuery()).getTotal());
        assertThrows(
                BizException.class,
                () ->
                        access().change(
                                        2,
                                        21,
                                        new ArticleAccessDtos.Change("FRIENDS", List.of("3"), 1)));
    }

    @Test
    void visibilityValidationVersionsAndRetriesDoNotChangeArticleReviewState() {
        peer(3);
        for (var request :
                List.of(
                        new ArticleAccessDtos.Change("FRIENDS", List.of(), 0),
                        new ArticleAccessDtos.Change("FRIENDS", List.of("3"), 0),
                        new ArticleAccessDtos.Change("PUBLIC", List.of("3"), 0),
                        new ArticleAccessDtos.Change("ANY", List.of(), 0)))
            assertThrows(
                    BizException.class, () -> tx().execute(s -> access().change(2, 21, request)));
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                21,
                                                new ArticleAccessDtos.Change(
                                                        "PRIVATE", List.of(), 0)));
        assertEquals(
                1,
                tx().execute(
                                s ->
                                        access().change(
                                                        2,
                                                        21,
                                                        new ArticleAccessDtos.Change(
                                                                "PRIVATE", List.of(), 0)))
                        .revision());
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                access().change(
                                                                2,
                                                                21,
                                                                new ArticleAccessDtos.Change(
                                                                        "PUBLIC", List.of(), 0))));
        assertEquals("PUBLISHED", service.get(2L, 21, false).state());
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                20,
                                                new ArticleAccessDtos.Change(
                                                        "PUBLIC", List.of(), 0)));
        assertEquals("PENDING_ADMIN", service.get(2L, 20, false).state());
        assertThrows(BizException.class, () -> service.get(3L, 20, false));
    }

    @Test
    void realViewsAreDeduplicatedPerAccountAndDayAndNeverCountTheAuthor() {
        peer(3);
        peer(4);
        assertEquals(0L, access().recordView(2, 21).get("viewCount"));
        assertEquals(1L, access().recordView(3, 21).get("viewCount"));
        assertEquals(1L, access().recordView(3, 21).get("viewCount"));
        assertEquals(2L, access().recordView(4, 21).get("viewCount"));
        assertEquals(2, service.get(2L, 21, false).viewCount());
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                21,
                                                new ArticleAccessDtos.Change(
                                                        "PRIVATE", List.of(), 0)));
        assertThrows(BizException.class, () -> access().recordView(3, 21));
        assertEquals(2, access().views(21));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=3");
        assertThrows(BizException.class, () -> access().recordView(3, 10));
    }

    @Test
    void hidingAnArticleIsPersistentIdempotentAndOnlyAffectsTheCurrentViewer() {
        peer(3);
        peer(4);
        tx().executeWithoutResult(s -> access().hidden(3, 21, true));
        tx().executeWithoutResult(s -> access().hidden(3, 21, true));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_article_hidden WHERE user_id=3",
                        Integer.class));
        assertThrows(BizException.class, () -> service.get(3L, 21, false));
        assertEquals("21", service.get(4L, 21, false).id());
        assertFalse(
                service.feed(3, null, false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("21")));
        assertTrue(
                service.feed(4, null, false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("21")));
        access().hidden(3, 21, false);
        assertEquals("21", service.get(3L, 21, false).id());
        assertThrows(BizException.class, () -> access().hidden(2, 21, true));
    }

    @Test
    void reportingHidesOnlyAfterSuccessfulCommitAndDoesNotGloballyUnpublish() {
        peer(3);
        peer(4);
        var report = new ReportRequest("ARTICLE", 21, "SPAM", "请求核查", "hide-report-001");
        tx().executeWithoutResult(
                        s -> {
                            service.report(3, report);
                            s.setRollbackOnly();
                        });
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_article_hidden", Integer.class));
        var first = tx().execute(s -> service.report(3, report));
        var retry = tx().execute(s -> service.report(3, report));
        assertEquals(first.get("id"), retry.get("id"));
        assertThrows(BizException.class, () -> service.get(3L, 21, false));
        assertEquals("PUBLISHED", service.get(4L, 21, false).state());
    }

    @Test
    void draftPublicationPreservesSelectedAudienceInTheSameTransaction() {
        peer(3);
        mutual(3);
        var drafts = drafts();
        var payload =
                new CommunityDraftDtos.Payload(
                        "好友分享",
                        "今天把自己的生活记录整理了一下，想和互相关注的好友分享。",
                        List.of("daily"),
                        List.of(),
                        List.of(),
                        "visibility-draft-01",
                        null,
                        21L,
                        1,
                        List.of(),
                        "FRIENDS",
                        List.of("3"));
        var saved = tx().execute(s -> drafts.create(2, payload));
        assertEquals("FRIENDS", saved.visibility());
        assertEquals(List.of("3"), saved.viewerIds());
        var published =
                tx().execute(
                                s ->
                                        drafts.publish(
                                                2,
                                                Long.parseLong(saved.id()),
                                                new CommunityDraftDtos.Publish(
                                                        saved.revision(),
                                                        "visibility-publish-01")));
        assertEquals("FRIENDS", published.visibility());
        assertEquals(List.of("3"), published.viewerIds());
        assertEquals("AI_PENDING", published.state());
        assertEquals(0, drafts.list(2, new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> service.get(3L, 21, false));
    }

    @Test
    void interactionNotificationsDoNotExposeArticlesAfterPrivacyChanges() {
        peer(3);
        peer(4);
        mutual(3);
        var community = CommunityTestMappers.community(jdbc);
        var root =
                community.addComment(
                        2, 21, new CommentDtos.CommentRequest("文章里的生活记录", "privacy-root-01"));
        var reply =
                community.addComment(
                        3,
                        21,
                        new CommentDtos.CommentRequest(
                                "回复作者的分享", "privacy-reply-01", Long.parseLong(root.id())));
        var notification =
                new CommunityNotificationService(
                        CommunityTestMappers.mapper(jdbc, CommunityNotificationMapper.class),
                        community);
        assertEquals(1, notification.summary(2).comments());
        var comment =
                community.addComment(
                        4,
                        21,
                        new CommentDtos.CommentRequest(
                                "回复另一位糖友", "privacy-reply-02", Long.parseLong(reply.id())));
        assertEquals(1, notification.summary(3).comments());
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                21,
                                                new ArticleAccessDtos.Change(
                                                        "PRIVATE", List.of(), 0)));
        assertEquals(0, notification.list(3, "COMMENT", new PageQuery()).getTotal());
        assertNotNull(comment);
    }

    private long createReport(String key) {
        return Long.parseLong(
                tx().execute(
                                s ->
                                        service.report(
                                                2,
                                                new ReportRequest(
                                                        "ARTICLE", 10, "SPAM", "请求核查", key)))
                        .get("id")
                        .toString());
    }

    @Test
    void reportFeedbackIsPrivateAndPersistsAfterTargetIsHidden() {
        long id = createReport("feedback-hidden-01");
        assertEquals("官方已发布", feedback().get(2, id).targetTitle());
        assertEquals("OPEN", feedback().get(2, id).state());
        jdbc.update("INSERT INTO user_account(id) VALUES(3)");
        assertEquals(0, feedback().mine(3, new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> feedback().get(3, id));
        assertThrows(BizException.class, () -> feedback().read(3, id));
        tx().executeWithoutResult(
                        s ->
                                service.resolveReport(
                                        99, id, new Resolution("HIDE_ARTICLE", "已核查并下架文章，感谢反馈。")));
        var result = feedback().get(2, id);
        assertEquals("RESOLVED", result.state());
        assertEquals("HIDE_ARTICLE", result.resolution());
        assertEquals("已核查并下架文章，感谢反馈。", result.resolutionNote());
        assertNotNull(result.resolvedAt());
        assertFalse(result.feedbackRead());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT publish_status FROM cms_news WHERE id=10", Integer.class));
        assertEquals(1, feedback().mine(2, new PageQuery()).getTotal());
        jdbc.update(
                "UPDATE cms_news SET"
                    + " is_deleted=1,deleted_at=CURRENT_TIMESTAMP,deleted_by=99,title='已修改标题' WHERE"
                    + " id=10");
        assertEquals("官方已发布", feedback().get(2, id).targetTitle());
    }

    @Test
    void reportFeedbackUnreadSummaryAndReadAreScopedAndIdempotent() {
        long first = createReport("feedback-read-01");
        feedback().read(2, first);
        assertFalse(feedback().get(2, first).feedbackRead());
        var notices =
                new CommunityNotificationService(
                        CommunityTestMappers.mapper(jdbc, CommunityNotificationMapper.class),
                        CommunityTestMappers.community(jdbc));
        assertEquals(0, notices.summary(2).reports());
        tx().executeWithoutResult(
                        s ->
                                service.resolveReport(
                                        99, first, new Resolution("DISMISS", "核查后未发现违规内容。")));
        long second = createReport("feedback-read-02");
        tx().executeWithoutResult(
                        s ->
                                service.resolveReport(
                                        99, second, new Resolution("RESOLVE", "已完成核查。")));
        assertEquals(2, notices.summary(2).reports());
        assertEquals(2, notices.summary(2).total());
        assertEquals(Long.toString(second), notices.summary(2).latestReportId());
        feedback().read(2, first);
        feedback().read(2, first);
        assertTrue(feedback().get(2, first).feedbackRead());
        assertFalse(feedback().get(2, second).feedbackRead());
        assertEquals(1, notices.summary(2).reports());
        feedback().read(2, second);
        assertEquals(0, notices.summary(2).total());
        assertNull(notices.summary(2).latestReportId());
    }

    @Test
    void failedOrRolledBackReportResolutionDoesNotCreateFeedback() {
        long id = createReport("feedback-rollback-01");
        assertThrows(
                BizException.class,
                () ->
                        tx().executeWithoutResult(
                                        s ->
                                                service.resolveReport(
                                                        99, id, new Resolution("DISMISS", " "))));
        tx().executeWithoutResult(
                        s -> {
                            service.resolveReport(99, id, new Resolution("DISMISS", "未发现问题"));
                            s.setRollbackOnly();
                        });
        assertEquals("OPEN", feedback().get(2, id).state());
        assertNull(feedback().get(2, id).resolvedAt());
        assertEquals(
                0,
                new CommunityNotificationService(
                                CommunityTestMappers.mapper(
                                        jdbc, CommunityNotificationMapper.class),
                                CommunityTestMappers.community(jdbc))
                        .summary(2)
                        .reports());
        tx().executeWithoutResult(
                        s -> service.resolveReport(99, id, new Resolution("DISMISS", "未发现问题")));
        assertThrows(
                BizException.class,
                () ->
                        tx().executeWithoutResult(
                                        s ->
                                                service.resolveReport(
                                                        99,
                                                        id,
                                                        new Resolution("RESOLVE", "不能重复处理"))));
        assertEquals("DISMISS", feedback().get(2, id).resolution());
    }

    @Test
    void reportFeedbackHttpUsesCurrentUserAndDoesNotExposeAdministrativeOrTargetPrivateFields()
            throws Exception {
        long id = createReport("feedback-http-01");
        tx().executeWithoutResult(
                        s ->
                                service.resolveReport(
                                        99, id, new Resolution("DISMISS", "感谢反馈，已完成核查。")));
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new CommunityReportFeedbackController(feedback()))
                        .setControllerAdvice(
                                new com.threadfit.glucopilot.common.exception
                                        .GlobalExceptionHandler())
                        .build();
        try {
            com.threadfit.glucopilot.common.context.RequestContext.setUserId(2L);
            var response =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                            "/api/v1/community/reports/mine"))
                            .andReturn()
                            .getResponse();
            assertEquals(200, response.getStatus());
            var json =
                    new tools.jackson.databind.ObjectMapper()
                            .readTree(response.getContentAsString());
            var report = json.get("data").get("records").get(0);
            assertEquals(Long.toString(id), report.get("id").asText());
            assertFalse(report.has("resolved_by"));
            assertFalse(report.has("reporter_id"));
            assertFalse(report.has("target_content"));
            assertEquals(
                    200,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.put(
                                            "/api/v1/community/reports/" + id + "/read"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertTrue(feedback().get(2, id).feedbackRead());
            jdbc.update("INSERT INTO user_account(id) VALUES(3)");
            com.threadfit.glucopilot.common.context.RequestContext.setUserId(3L);
            assertEquals(
                    404,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                            "/api/v1/community/reports/" + id))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    404,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.put(
                                            "/api/v1/community/reports/" + id + "/read"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            com.threadfit.glucopilot.common.context.RequestContext.clear();
            assertEquals(
                    401,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                            "/api/v1/community/reports/mine"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        } finally {
            com.threadfit.glucopilot.common.context.RequestContext.clear();
        }
    }

    @Test
    void reportFeedbackPaginationAndInactiveAccountsAreValidated() {
        for (String key : List.of("feedback-page-01", "feedback-page-02", "feedback-page-03"))
            createReport(key);
        var q = new PageQuery();
        q.setPageSize(2);
        var first = feedback().mine(2, q);
        assertEquals(3, first.getTotal());
        assertEquals(2, first.getRecords().size());
        q.setPageNum(2);
        assertEquals(1, feedback().mine(2, q).getRecords().size());
        q.setPageSize(51);
        assertThrows(BizException.class, () -> feedback().mine(2, q));
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");
        assertThrows(BizException.class, () -> feedback().mine(2, new PageQuery()));
    }

    @Test
    void draftReadAndListAcceptMysqlDatetimeWithoutTimestampCast() {
        var saved =
                tx().execute(
                                s ->
                                        drafts().create(
                                                        2,
                                                        draft(
                                                                "MySQL datetime regression",
                                                                "draft-mysql-time",
                                                                null)));
        var original = CommunityTestMappers.mapper(jdbc, CommunityDraftMapper.class);
        long id = Long.parseLong(saved.id());
        var row = new HashMap<>(original.selectOwnedDraft(id, 2, false, false).getFirst());
        row.put("updated_at", saved.updatedAt());
        var mysql = mock(CommunityDraftMapper.class);
        org.mockito.Mockito.when(mysql.selectActiveUser(2, false)).thenReturn(List.of(2L));
        org.mockito.Mockito.when(mysql.selectOwnedDraft(id, 2, false, false))
                .thenReturn(List.of(row));
        org.mockito.Mockito.when(mysql.countDrafts(2)).thenReturn(1L);
        org.mockito.Mockito.when(mysql.selectDrafts(2, 20, 0)).thenReturn(List.of(row));
        var drafts =
                new CommunityDraftService(
                        mysql,
                        new com.threadfit.glucopilot.common.util.JsonUtils(
                                new tools.jackson.databind.ObjectMapper()),
                        service,
                        CommunityTestMappers.access(jdbc));
        assertEquals(saved.updatedAt(), drafts.get(2, Long.parseLong(saved.id())).updatedAt());
        assertEquals(saved.id(), drafts.list(2, new PageQuery()).getRecords().getFirst().id());
    }

    @Test
    void draftHttpAcceptsMiniProgramPayloadWithEmptySourceAndMedia() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new CommunityDraftController(drafts()))
                        .build();
        com.threadfit.glucopilot.common.context.RequestContext.setUserId(2L);
        try {
            var result =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post("/api/v1/community/drafts")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"title\":\"draft"
                                                        + " title\",\"body\":\"unfinished\",\"tagCodes\":[\"diet\"],\"customTags\":[],\"imageMediaIds\":[],\"videoMediaIds\":[],\"sourceArticleId\":null,\"sourceRevision\":null,\"revision\":null,\"clientRequestId\":\"draft-http-001\"}"))
                            .andReturn();
            assertEquals(
                    200,
                    result.getResponse().getStatus(),
                    () -> String.valueOf(result.getResolvedException()));
            assertEquals(1, drafts().list(2, new PageQuery()).getTotal());
        } finally {
            com.threadfit.glucopilot.common.context.RequestContext.clear();
        }
    }

    private CommunityDraftDtos.Payload draft(String body, String key, Integer revision) {
        return new CommunityDraftDtos.Payload(
                "我的草稿", body, List.of("diet"), List.of(), List.of(), key, revision, 21L, 1);
    }

    @Test
    void draftsSaveIncompleteContentWithoutChangingPublishedArticleAndEnforceOwnerRevision() {
        var drafts = drafts();
        var first = tx().execute(s -> drafts.create(2, draft("还没写完", "draft-save-001", null)));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT publish_status FROM cms_news WHERE id=21", Integer.class));
        assertEquals("PUBLISHED", service.get(2L, 21, false).state());
        assertEquals(
                first.id(),
                tx().execute(s -> drafts.create(2, draft("还没写完", "draft-save-001", null))).id());
        long id = Long.parseLong(first.id());
        var request = draft("继续编辑了草稿", "draft-edit-001", 1);
        var edited = tx().execute(s -> drafts.edit(2, id, request));
        assertEquals(2, edited.revision());
        assertEquals(2, tx().execute(s -> drafts.edit(2, id, request)).revision());
        assertThrows(
                BizException.class,
                () -> tx().execute(s -> drafts.edit(2, id, draft("另一个修改", "draft-edit-002", 1))));
        assertThrows(BizException.class, () -> drafts.get(3, id));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s -> {
                                            drafts.delete(2, id, 1);
                                            return null;
                                        }));
        tx().executeWithoutResult(s -> drafts.delete(2, id, 2));
        assertThrows(BizException.class, () -> drafts.get(2, id));
    }

    @Test
    void draftPublishingIsAtomicIdempotentAndStillRequiresBothReviews() {
        var drafts = drafts();
        var saved =
                tx().execute(
                                s ->
                                        drafts.create(
                                                2,
                                                draft(
                                                        "今天认真记录了自己的早餐和运动情况，希望分享坚持记录的经验。",
                                                        "draft-publish-01",
                                                        null)));
        long id = Long.parseLong(saved.id());
        var request = new CommunityDraftDtos.Publish(1, "draft-publish-op");
        var article = tx().execute(s -> drafts.publish(2, id, request));
        assertEquals("21", article.id());
        assertEquals("AI_PENDING", article.state());
        assertEquals(2, article.revision());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT publish_status FROM cms_news WHERE id=21", Integer.class));
        assertEquals("21", tx().execute(s -> drafts.publish(2, id, request)).id());
        assertEquals(0, drafts.list(2, new PageQuery()).getTotal());
        assertThrows(BizException.class, () -> drafts.get(2, id));
        jdbc.update(
                "UPDATE community_article SET state='AI_REJECTED',ai_verdict='REJECT' WHERE"
                        + " news_id=21");
        assertEquals(0, drafts.list(2, new PageQuery()).getTotal());
        assertEquals(
                "AI_REJECTED",
                service.mine(2, new PageQuery()).getRecords().stream()
                        .filter(a -> a.id().equals("21"))
                        .findFirst()
                        .orElseThrow()
                        .state());
        var edited =
                tx().execute(
                                s ->
                                        service.edit(
                                                2,
                                                21,
                                                new Submit(
                                                        "审核后重新修改",
                                                        "今天认真补充了早餐和运动的真实记录，希望和大家交流坚持记录的小经验。",
                                                        List.of("diet"),
                                                        List.of(),
                                                        "rejected-edit-01",
                                                        2)));
        assertEquals("AI_PENDING", edited.state());
        assertEquals(0, drafts.list(2, new PageQuery()).getTotal());
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                drafts.publish(
                                                        2,
                                                        id,
                                                        new CommunityDraftDtos.Publish(
                                                                1, "different-publish"))));
    }

    @Test
    void invalidOrStaleDraftPublishPreservesSavedDraftAndNeverOverwritesArticle() {
        var drafts = drafts();
        var saved = tx().execute(s -> drafts.create(2, draft("短内容", "draft-invalid-01", null)));
        long id = Long.parseLong(saved.id());
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                drafts.publish(
                                                        2,
                                                        id,
                                                        new CommunityDraftDtos.Publish(
                                                                1, "draft-invalid-p1"))));
        assertEquals(1, drafts.list(2, new PageQuery()).getTotal());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT publish_status FROM cms_news WHERE id=21", Integer.class));
        jdbc.update("UPDATE community_article SET revision=2 WHERE news_id=21");
        tx().execute(
                        s ->
                                drafts.edit(
                                        2,
                                        id,
                                        draft(
                                                "今天认真记录了自己的早餐和运动情况，希望分享坚持记录的经验。",
                                                "draft-invalid-e1",
                                                1)));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                drafts.publish(
                                                        2,
                                                        id,
                                                        new CommunityDraftDtos.Publish(
                                                                2, "draft-invalid-p2"))));
        assertEquals(1, drafts.list(2, new PageQuery()).getTotal());
    }

    private void image(long id, long owner, int biz) {
        jdbc.update(
                "INSERT INTO"
                    + " media_file(id,user_id,biz_type,bucket_name,storage_key,file_url,mime_type,size_bytes)"
                    + " VALUES(?,?,?,'test-bucket','test-key','unused','image/png',4)",
                id,
                owner,
                biz);
    }

    @Test
    void imageEditsPersistOrderAndIdempotencyAndRequireOwnedAvailableArticlePhotos() {
        image(50, 2, 7);
        image(51, 2, 7);
        image(52, 3, 7);
        image(53, 2, 3);
        String body = "今天认真记录了自己的早餐和日常情况，希望和大家分享坚持记录的经验。";
        var request =
                new Submit(
                        "我的配图文章",
                        body,
                        List.of("diet"),
                        List.of(),
                        "image-edit-001",
                        1,
                        List.of("51", "50"));
        var saved = tx().execute(s -> service.edit(2, 20, request));
        assertEquals(
                List.of("51", "50"), saved.images().stream().map(ArticleImage::mediaId).toList());
        assertEquals(2, saved.revision());
        assertEquals("AI_PENDING", saved.state());
        assertEquals(2, tx().execute(s -> service.edit(2, 20, request)).revision());
        var changed =
                new Submit(
                        "我的配图文章",
                        body,
                        List.of("diet"),
                        List.of(),
                        "image-edit-001",
                        1,
                        List.of("50", "51"));
        assertThrows(BizException.class, () -> tx().execute(s -> service.edit(2, 20, changed)));
        for (var ids : List.of(List.of("52"), List.of("53"), List.of("50", "50"), List.of("999"))) {
            var invalid =
                    new Submit(
                            "我的配图文章", body, List.of("diet"), List.of(), "image-edit-002", 2, ids);
            assertThrows(BizException.class, () -> tx().execute(s -> service.edit(2, 20, invalid)));
        }
        assertEquals(
                List.of("51", "50"),
                service.get(2L, 20, false).images().stream().map(ArticleImage::mediaId).toList());
        var removed =
                tx().execute(
                                s ->
                                        service.edit(
                                                2,
                                                20,
                                                new Submit(
                                                        "我的配图文章",
                                                        body,
                                                        List.of("diet"),
                                                        List.of(),
                                                        "image-edit-003",
                                                        2,
                                                        List.of())));
        assertTrue(removed.images().isEmpty());
    }

    @Test
    void pendingImagesAreOwnerOnlyAndAdminMustReadReferencedCurrentArticle() {
        image(50, 2, 7);
        jdbc.update("INSERT INTO user_account(id) VALUES(3)");
        jdbc.update(
                "INSERT INTO community_article_image(news_id,media_id,sort_no) VALUES(20,50,0)");
        var mapper = mock(com.threadfit.glucopilot.file.mapper.MediaFileMapper.class);
        var storage = mock(com.threadfit.glucopilot.file.storage.ObjectStorageService.class);
        var photo = new com.threadfit.glucopilot.file.entity.MediaFile();
        photo.setId(50L);
        photo.setUserId(2L);
        photo.setBizType(7);
        photo.setFileStatus(1);
        photo.setIsDeleted(0);
        photo.setMimeType("image/png");
        photo.setSizeBytes(4L);
        photo.setStorageProvider(1);
        photo.setBucketName("test-bucket");
        photo.setStorageKey("test-key");
        org.mockito.Mockito.when(mapper.selectById(50L)).thenReturn(photo);
        org.mockito.Mockito.when(storage.provider())
                .thenReturn(com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider.MINIO);
        org.mockito.Mockito.when(storage.bucket()).thenReturn("test-bucket");
        org.mockito.Mockito.when(storage.getObject("test-key")).thenReturn(new byte[] {1, 2, 3, 4});
        var images =
                new CommunityArticleImageService(
                        CommunityTestMappers.mapper(jdbc, CommunityArticleImageMapper.class),
                        mapper,
                        storage);
        assertEquals(4, images.read(2L, null, 50).bytes().length);
        assertThrows(BizException.class, () -> images.read(3L, null, 50));
        assertEquals(4, images.read(null, 20L, 50).bytes().length);
        assertThrows(BizException.class, () -> images.read(null, 21L, 50));
        jdbc.update("UPDATE cms_news SET publish_status=1 WHERE id=20");
        jdbc.update("UPDATE community_article SET state='PUBLISHED' WHERE news_id=20");
        assertEquals(4, images.read(3L, null, 50).bytes().length);
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                20,
                                                new ArticleAccessDtos.Change(
                                                        "PRIVATE", List.of(), 0)));
        assertThrows(BizException.class, () -> images.read(3L, null, 50));
        assertEquals(4, images.read(2L, null, 50).bytes().length);
        assertEquals(4, images.read(null, 20L, 50).bytes().length);
        mutual(3);
        tx().execute(
                        s ->
                                access().change(
                                                2,
                                                20,
                                                new ArticleAccessDtos.Change(
                                                        "FRIENDS", List.of("3"), 1)));
        assertEquals(4, images.read(3L, null, 50).bytes().length);
        jdbc.update(
                "UPDATE community_follow SET following=0 WHERE follower_id=3 AND target_key='u2'");
        assertThrows(BizException.class, () -> images.read(3L, null, 50));
        jdbc.update("UPDATE community_article SET state='HIDDEN' WHERE news_id=20");
        assertThrows(BizException.class, () -> images.read(3L, null, 50));
        photo.setBizType(3);
        assertThrows(BizException.class, () -> images.read(2L, null, 50));
        photo.setBizType(7);
        photo.setSizeBytes(5242881L);
        assertThrows(BizException.class, () -> images.read(2L, null, 50));
    }

    @Test
    void videoOnlyPostPersistsThroughDraftPublishAndEnforcesOwnershipAndIdempotency() {
        image(60, 2, 8);
        image(61, 3, 8);
        jdbc.update("UPDATE media_file SET mime_type='video/mp4' WHERE id IN (60,61)");
        var request =
                new Submit(
                        "视频记录",
                        "",
                        List.of("daily"),
                        List.of(),
                        "video-edit-001",
                        1,
                        List.of(),
                        List.of("60"));
        var saved = tx().execute(s -> service.edit(2, 20, request));
        assertEquals("AI_PENDING", saved.state());
        assertTrue(saved.images().isEmpty());
        assertEquals("60", saved.videos().getFirst().mediaId());
        assertEquals(2, tx().execute(s -> service.edit(2, 20, request)).revision());
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                service.edit(
                                                        2,
                                                        20,
                                                        new Submit(
                                                                "视频记录",
                                                                "",
                                                                List.of("daily"),
                                                                List.of(),
                                                                "video-edit-002",
                                                                2,
                                                                List.of(),
                                                                List.of("61")))));
        var p =
                new CommunityDraftDtos.Payload(
                        "视频草稿",
                        "",
                        List.of("daily"),
                        List.of(),
                        List.of(),
                        "video-draft-01",
                        null,
                        21L,
                        1,
                        List.of("60"));
        var draft = tx().execute(s -> drafts().create(2, p));
        assertEquals("60", draft.videos().getFirst().mediaId());
        var article =
                tx().execute(
                                s ->
                                        drafts().publish(
                                                        2,
                                                        Long.parseLong(draft.id()),
                                                        new CommunityDraftDtos.Publish(
                                                                1, "video-publish-01")));
        assertEquals("60", article.videos().getFirst().mediaId());
        assertTrue(article.body().isEmpty());
        assertEquals("AI_PENDING", article.state());
    }

    @Test
    void authorFeedNeverIncludesPendingHiddenOrDeletedArticlesAndTagsStayOwnerScoped() {
        var page = service.byAuthor(2, "u2", new PageQuery());
        assertEquals(List.of("21"), page.getRecords().stream().map(Article::id).toList());
        jdbc.update("INSERT INTO community_tag(code,name) VALUES('custom_test','我的专用标签')");
        jdbc.update(
                "INSERT INTO community_article_tag(news_id,tag_id) SELECT 20,id FROM community_tag"
                        + " WHERE code='custom_test'");
        assertTrue(service.myTags(2).stream().anyMatch(t -> t.code().equals("custom_test")));
        jdbc.update("INSERT INTO user_account(id) VALUES(3)");
        assertTrue(service.myTags(3).isEmpty());
        jdbc.update("UPDATE community_article SET state='HIDDEN' WHERE news_id=21");
        assertEquals(0, service.byAuthor(2, "u2", new PageQuery()).getTotal());
    }

    @Test
    void defaultIncludesOfficialAndCommunityAtAllStatesWithRealPublishers() {
        var page = list(null, null, null);
        assertEquals(5, page.getTotal());
        assertEquals(
                List.of("21", "20", "12", "11", "10"),
                page.getRecords().stream().map(Article::id).toList());
        var user = page.getRecords().getFirst();
        assertEquals("COMMUNITY", user.source());
        assertEquals("用户小明", user.author().nickname());
        assertEquals("u2", user.author().key());
        var official = page.getRecords().getLast();
        assertEquals("OFFICIAL", official.source());
        assertEquals("社区编辑部", official.author().nickname());
        assertEquals("b1", official.author().key());
        assertNotNull(official.createdAt());
        assertEquals("", official.body());
    }

    @Test
    void filtersStatusSourceTitleAndPublisherWithoutSqlWildcards() {
        assertEquals(2, list("PUBLISHED", null, null).getTotal());
        assertEquals("11", list("DRAFT", null, null).getRecords().getFirst().id());
        assertEquals("12", list("HIDDEN", null, null).getRecords().getFirst().id());
        assertEquals(2, list(null, "COMMUNITY", null).getTotal());
        assertEquals(3, list(null, "OFFICIAL", null).getTotal());
        assertEquals("20", list(null, null, "等待").getRecords().getFirst().id());
        assertEquals(2, list(null, null, "小明").getTotal());
        assertEquals(3, list(null, null, "编辑部").getTotal());
        assertEquals(0, list(null, null, "%_'").getTotal());
    }

    @Test
    void detailLoadsUnpublishedContentAndDoesNotMisattributeInactiveAuthor() {
        jdbc.update("UPDATE user_account SET account_status=0 WHERE id=2");
        jdbc.update("UPDATE user_profile SET nickname='新昵称' WHERE user_id=2");
        var item = service.get(null, 20, true);
        assertEquals("完整正文", item.body());
        assertEquals("PENDING_ADMIN", item.state());
        assertEquals("新昵称", item.author().nickname());
        assertEquals("u2", item.author().key());
        assertThrows(BizException.class, () -> service.get(9L, 20, false));
        assertThrows(BizException.class, () -> service.get(null, 22, true));
    }

    @Test
    void pagingTotalsAndInvalidFiltersAreValidated() {
        var q = new PageQuery();
        q.setPageSize(2);
        q.setPageNum(2);
        var page = service.adminArticles(null, null, null, q);
        assertEquals(5, page.getTotal());
        assertEquals(List.of("12", "11"), page.getRecords().stream().map(Article::id).toList());
        assertThrows(BizException.class, () -> list("INVALID", null, null));
        assertThrows(BizException.class, () -> list(null, "INVALID", null));
        assertThrows(BizException.class, () -> list(null, null, "字".repeat(81)));
        q.setPageSize(51);
        assertThrows(BizException.class, () -> service.adminArticles(null, null, null, q));
    }

    @Test
    void adminReviewPreservesAiGateAndVersionThenRecordsPublisherAndOperator() {
        assertThrows(
                BizException.class, () -> service.review(99, 20, new Review(2, "APPROVE", "通过")));
        jdbc.update("UPDATE community_article SET ai_verdict='PENDING' WHERE news_id=20");
        assertThrows(
                BizException.class, () -> service.review(99, 20, new Review(1, "APPROVE", "通过")));
        jdbc.update("UPDATE community_article SET ai_verdict='PASS' WHERE news_id=20");
        var item = service.review(99, 20, new Review(1, "APPROVE", "核查通过"));
        assertEquals("PUBLISHED", item.state());
        assertEquals("u2", item.author().key());
        assertEquals(
                99,
                jdbc.queryForObject(
                        "SELECT reviewed_by FROM community_article WHERE news_id=20", Long.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_moderation_log WHERE actor_id=99 AND"
                                + " decision='APPROVE'",
                        Integer.class));
        assertThrows(
                BizException.class, () -> service.review(99, 10, new Review(1, "APPROVE", "通过")));
    }

    @Test
    void reportQueueLoadsArticleContextAndPersistsAdministrativeResolution() {
        jdbc.update(
                "INSERT INTO"
                    + " community_report(reporter_id,target_type,target_id,reason,details,client_request_id)"
                    + " VALUES(2,'ARTICLE',10,'MISINFORMATION','核查请求','report-admin-01')");
        var page = service.reports("OPEN", new PageQuery());
        assertEquals(1, page.getTotal());
        assertTrue(page.getRecords().getFirst().get("target_content").toString().contains("官方已发布"));
        long id = Long.parseLong(page.getRecords().getFirst().get("id").toString());
        service.resolveReport(99, id, new Resolution("DISMISS", "核查后未发现问题"));
        assertEquals(0, service.reports("OPEN", new PageQuery()).getTotal());
        assertEquals(1, service.reports("DISMISSED", new PageQuery()).getTotal());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    @Test
    void customTagsPersistWithEditsAndRetriesButStayOutOfPublicNavigationUntilPublication() {
        var request =
                new Submit(
                        "我的饮食记录",
                        "今天把一餐的搭配认真记了下来，希望和大家交流坚持记录的经验。",
                        List.of("diet"),
                        List.of(" #我的早餐 "),
                        "custom-edit-01",
                        1);
        var saved = tx().execute(s -> service.edit(2, 20, request));
        assertEquals(2, saved.tags().size());
        assertTrue(saved.tags().stream().anyMatch(t -> t.name().equals("我的早餐")));
        String code =
                saved.tags().stream()
                        .filter(t -> t.name().equals("我的早餐"))
                        .findFirst()
                        .orElseThrow()
                        .code();
        assertTrue(code.startsWith("custom_"));
        assertEquals(6, service.tags().size());
        var retry = tx().execute(s -> service.edit(2, 20, request));
        assertEquals(saved.revision(), retry.revision());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_tag WHERE code=?", Integer.class, code));
        jdbc.update("UPDATE community_article SET state='PUBLISHED' WHERE news_id=20");
        jdbc.update("UPDATE cms_news SET publish_status=1 WHERE id=20");
        assertTrue(service.tags().stream().anyMatch(t -> t.code().equals(code)));
    }

    @Test
    void customTagValidationAndVersionConflictsCannotLeakNewTags() {
        String body = "今天把一餐的搭配认真记了下来，希望和大家交流坚持记录的经验。";
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM community_tag", Long.class);
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                service.edit(
                                                        2,
                                                        20,
                                                        new Submit(
                                                                "分享记录",
                                                                body,
                                                                List.of(),
                                                                List.of("新标签"),
                                                                "custom-invalid-1",
                                                                9))));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM community_tag", Long.class));
        for (var custom :
                List.of(
                        List.of("<script>"),
                        List.of("字".repeat(17)),
                        List.of("标签甲", "标签乙", "标签丙", "标签丁")))
            assertThrows(
                    BizException.class,
                    () ->
                            tx().execute(
                                            s ->
                                                    service.edit(
                                                            2,
                                                            20,
                                                            new Submit(
                                                                    "分享记录",
                                                                    body,
                                                                    List.of(),
                                                                    custom,
                                                                    "custom-invalid-2",
                                                                    1))));
        jdbc.update("UPDATE community_tag SET enabled=0 WHERE code='diet'");
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                service.edit(
                                                        2,
                                                        20,
                                                        new Submit(
                                                                "分享记录",
                                                                body,
                                                                List.of(),
                                                                List.of("饮食控糖"),
                                                                "custom-invalid-3",
                                                                1))));
    }

    @Test
    void realMapperSubmissionStoresCustomTagsAndIdempotencyThenAuthorIdentityStaysConsistent() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(
                configuration,
                new com.baomidou.mybatisplus.core.config.GlobalConfig()
                        .setDbConfig(
                                new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig())
                        .setMetaObjectHandler(
                                new com.threadfit.glucopilot.common.mybatis
                                        .AuditMetaObjectHandler()));
        configuration.setEnvironment(
                new org.apache.ibatis.mapping.Environment(
                        "test",
                        new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
                        jdbc.getDataSource()));
        configuration.addMapper(CmsNewsMapper.class);
        var template =
                new org.mybatis.spring.SqlSessionTemplate(
                        new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder()
                                .build(configuration));
        var community = CommunityTestMappers.community(jdbc);
        var posting =
                new CommunityPublicationService(
                        CommunityTestMappers.mapper(jdbc, CommunityPublicationMapper.class),
                        template.getMapper(CmsNewsMapper.class),
                        community,
                        CommunityTestMappers.access(jdbc),
                        CommunityTestMappers.recommendation(jdbc),
                        CommunityTestMappers.reports(jdbc),
                        CommunityTestMappers.adminComments(jdbc));
        var request =
                new Submit(
                        "新投稿分享",
                        "今天认真记录了自己的早餐，希望能和大家分享一下坚持记录的经验。",
                        List.of("diet"),
                        List.of("我的早餐"),
                        "real-submit-001",
                        null);
        var first = tx().execute(s -> posting.submit(2, request));
        var retry = tx().execute(s -> posting.submit(2, request));
        assertEquals(first.id(), retry.id());
        assertEquals("AI_PENDING", first.state());
        assertEquals(2, first.tags().size());
        assertEquals("u2", community.newsAuthor(null, Long.parseLong(first.id())).key());
        assertEquals("用户小明", community.newsAuthor(null, Long.parseLong(first.id())).nickname());
        assertEquals(
                1,
                posting.mine(2, new PageQuery()).getRecords().stream()
                        .filter(a -> a.id().equals(first.id()))
                        .count());
        assertFalse(
                posting.feed(2, null, false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals(first.id())));
        assertThrows(BizException.class, () -> posting.get(9L, Long.parseLong(first.id()), false));
    }

    @Test
    void sameCustomNameSharesStableCodeAcrossArticlesAndNormalizesBuiltinNames() {
        String body = "今天把一餐的搭配认真记了下来，希望和大家交流坚持记录的经验。";
        var first =
                tx().execute(
                                s ->
                                        service.edit(
                                                2,
                                                20,
                                                new Submit(
                                                        "分享记录",
                                                        body,
                                                        List.of(),
                                                        List.of(" ＡＢＣ ", "饮食控糖"),
                                                        "custom-shared-1",
                                                        1)));
        var second =
                tx().execute(
                                s ->
                                        service.edit(
                                                2,
                                                21,
                                                new Submit(
                                                        "分享记录",
                                                        body,
                                                        List.of(),
                                                        List.of("abc"),
                                                        "custom-shared-2",
                                                        1)));
        assertTrue(first.tags().stream().anyMatch(t -> t.code().equals("diet")));
        assertEquals(
                first.tags().stream()
                        .filter(t -> t.code().startsWith("custom_"))
                        .findFirst()
                        .orElseThrow()
                        .code(),
                second.tags().getFirst().code());
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM community_tag", Integer.class));
    }

    @Test
    void administratorCategoriesAppearBeforeAnyArticleAndDeletionCannotBeRecreatedByUsers() {
        var tags =
                new CommunityTagService(
                        CommunityTestMappers.mapper(jdbc, CommunityTagMapper.class));
        var created = tx().execute(s -> tags.create(new CommunityTagDtos.Change("控糖早餐", 20, 1)));
        assertEquals("OFFICIAL", created.type());
        assertTrue(created.code().startsWith("topic_"));
        assertTrue(
                service.tags(Long.valueOf(2)).stream()
                        .anyMatch(t -> t.code().equals(created.code())));
        assertEquals(
                created.code(),
                tx().execute(s -> tags.create(new CommunityTagDtos.Change("控糖早餐", 20, 1))).code());
        tx().executeWithoutResult(s -> tags.remove(created.code()));
        tx().executeWithoutResult(s -> tags.remove(created.code()));
        assertFalse(
                service.tags(Long.valueOf(2)).stream()
                        .anyMatch(t -> t.code().equals(created.code())));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                service.edit(
                                                        2,
                                                        20,
                                                        new Submit(
                                                                "分享记录",
                                                                "今天认真记录早餐和血糖，希望分享坚持记录的日常经验。",
                                                                List.of(created.code()),
                                                                List.of(),
                                                                "deleted-topic-1",
                                                                1))));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                service.edit(
                                                        2,
                                                        20,
                                                        new Submit(
                                                                "分享记录",
                                                                "今天认真记录早餐和血糖，希望分享坚持记录的日常经验。",
                                                                List.of(),
                                                                List.of("控糖早餐"),
                                                                "deleted-topic-2",
                                                                1))));
        assertEquals(1, tags.list("早餐", "OFFICIAL", 0, new PageQuery()).getTotal());
        tx().execute(s -> tags.update(created.code(), new CommunityTagDtos.Change("控糖早餐", 2, 1)));
        assertTrue(
                service.tags(Long.valueOf(2)).stream()
                        .anyMatch(t -> t.code().equals(created.code())));
    }

    @Test
    void otherIncludesVisibleCustomArticlesWithoutExposingPrivateUnpublishedNames() {
        jdbc.update(
                "INSERT INTO community_tag(code,name,sort_no)"
                    + " VALUES('other','其他',9999),('custom_public','早餐搭配',100),('custom_private','私人标签',100)");
        jdbc.update(
                "INSERT INTO community_article_tag(news_id,tag_id) SELECT 21,id FROM community_tag"
                        + " WHERE code='custom_public'");
        jdbc.update(
                "INSERT INTO community_article_tag(news_id,tag_id) SELECT 20,id FROM community_tag"
                        + " WHERE code='custom_private'");
        var available = service.tags(Long.valueOf(2));
        assertTrue(
                available.stream()
                        .anyMatch(
                                t -> t.code().equals("custom_public") && t.type().equals("USER")));
        assertFalse(available.stream().anyMatch(t -> t.code().equals("custom_private")));
        assertTrue(
                service.feed(2, "other", false, null, new PageQuery()).getRecords().stream()
                        .anyMatch(a -> a.id().equals("21")));
        var tags =
                new CommunityTagService(
                        CommunityTestMappers.mapper(jdbc, CommunityTagMapper.class));
        tx().executeWithoutResult(s -> tags.remove("custom_public"));
        assertTrue(service.feed(2, "other", false, null, new PageQuery()).getRecords().isEmpty());
        assertTrue(
                service.myTags(2, true).stream()
                        .anyMatch(t -> t.code().equals("custom_public") && t.enabled() == 0));
        assertFalse(service.myTags(2).stream().anyMatch(t -> t.code().equals("custom_public")));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_article_tag a JOIN community_tag t ON"
                                + " t.id=a.tag_id WHERE a.news_id=21 AND t.code='custom_public'",
                        Integer.class));
        assertNotNull(service.get(2L, 21, false));
    }

    @Test
    void otherIsReservedAndOldDraftCannotSaveOrPublishRemovedTopics() {
        jdbc.update("INSERT INTO community_tag(code,name,sort_no) VALUES('other','其他',9999)");
        var tags =
                new CommunityTagService(
                        CommunityTestMappers.mapper(jdbc, CommunityTagMapper.class));
        assertThrows(
                BizException.class, () -> tx().executeWithoutResult(s -> tags.remove("other")));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                tags.update(
                                                        "other",
                                                        new CommunityTagDtos.Change("别的", 2, 1))));
        var request =
                new CommunityDraftDtos.Payload(
                        "草稿",
                        "还没写完",
                        List.of("diet"),
                        List.of(),
                        List.of(),
                        "topic-draft-01",
                        null,
                        null,
                        null,
                        List.of());
        var draft = tx().execute(s -> drafts().create(2, request));
        assertNotNull(draft);
        tx().executeWithoutResult(s -> tags.remove("diet"));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                drafts().create(
                                                                2,
                                                                new CommunityDraftDtos.Payload(
                                                                        "新草稿",
                                                                        "",
                                                                        List.of("diet"),
                                                                        List.of(),
                                                                        List.of(),
                                                                        "topic-draft-02",
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        List.of()))));
        assertThrows(
                BizException.class,
                () ->
                        tx().execute(
                                        s ->
                                                drafts().publish(
                                                                2,
                                                                Long.parseLong(draft.id()),
                                                                new CommunityDraftDtos.Publish(
                                                                        1, "topic-publish-1"))));
        assertEquals("草稿", drafts().get(2, Long.parseLong(draft.id())).title());
    }

    @Test
    void moderationQueueRequiresMatchingVersionAndNeverPublishesByItself() {
        var queue =
                new CommunityModerationQueue(
                        CommunityTestMappers.mapper(jdbc, CommunityModerationMapper.class),
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())),
                        service);
        jdbc.update("UPDATE community_article SET state='AI_PENDING' WHERE news_id=20");
        var old = queue.claim();
        assertNotNull(old);
        assertNull(queue.claim());
        jdbc.update("UPDATE community_article SET revision=2,state='AI_PENDING' WHERE news_id=20");
        var current = queue.claim();
        assertFalse(queue.finish(old, ModerationVerdict.pass("旧结果"), "model", "moderation@v1"));
        assertTrue(
                queue.finish(
                        current, ModerationVerdict.pass("初审通过，等待终审"), "model", "moderation@v1"));
        assertEquals("PENDING_ADMIN", service.get(null, 20, true).state());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT publish_status FROM cms_news WHERE id=20", Integer.class));
        assertFalse(
                queue.finish(current, ModerationVerdict.pass("重复结果"), "model", "moderation@v1"));
    }

    @Test
    void interruptedModerationBecomesRetryableErrorInsteadOfAutomaticPass() {
        var queue =
                new CommunityModerationQueue(
                        CommunityTestMappers.mapper(jdbc, CommunityModerationMapper.class),
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())),
                        service);
        jdbc.update(
                "UPDATE community_article SET state='AI_REVIEWING',updated_at=? WHERE news_id=20",
                java.time.LocalDateTime.now().minusMinutes(10));
        assertNull(queue.claim());
        assertEquals("AI_ERROR", service.get(null, 20, true).state());
        tx().executeWithoutResult(s -> service.retry(2, 20, false));
        assertNotNull(queue.claim());
    }
}
