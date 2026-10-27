package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的评论」在真实库上的行为。
 * <p>
 * 这个列表存在的理由是两件公开列表<b>刻意做不到</b>的事：
 * <ol>
 *   <li>{@code comments()} 只按文章维度查询，用户发完评论就再也找不回自己说过什么；</li>
 *   <li>被 AI 隐藏的评论在公开列表里直接消失，作者若不被告知，只会以为"发了却没有"。</li>
 * </ol>
 * 因此这里断言的重点是：只返回自己的、隐藏的也要返回、自己删掉的不返回、
 * 文章没了记录仍在（用 {@code articleAvailable} 表达而不是整条吞掉）。
 */
class CommunityMyCommentsTest {

    private JdbcTemplate jdbc;
    private Connection migrationConnection;
    private CommunityService service;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:my_comments_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        migrationConnection = ds.getConnection();

        String schema = new ClassPathResource("db/migration/V1__init_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String table : List.of("cms_news", "media_file")) {
            int start = schema.indexOf("CREATE TABLE " + table + " (");
            String ddl = schema.substring(start, schema.indexOf(";", start))
                    .replace("CONSTRAINT pk_id PRIMARY KEY", "PRIMARY KEY");
            try (var statement = migrationConnection.createStatement()) {
                statement.execute(ddl);
            }
        }
        jdbc.execute("CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT DEFAULT 1,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar_url VARCHAR(512),is_deleted INT DEFAULT 0)");
        for (String file : List.of("V29__community_interactions.sql", "V31__community_publication_moderation.sql",
                "V32__community_comment_replies.sql", "V33__community_article_images.sql",
                "V36__community_comment_like.sql")) {
            // V33 里有 MySQL 的 DROP CHECK，H2 要换成 DROP CONSTRAINT（与既有社区测试同一处理）
            String script = new ClassPathResource("db/migration/" + file)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("DROP CHECK chk_mf_biz", "DROP CONSTRAINT chk_mf_biz");
            ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                    new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
            migration.setSqlScriptEncoding("UTF-8");
            migration.populate(migrationConnection);
        }
        CommunityTestSchema.applyDraftMigration(migrationConnection);
        CommunityTestSchema.applyModerationMigration(migrationConnection);

        jdbc.update("INSERT INTO user_account(id) VALUES(2),(3)");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(2,'我'),(3,'别人')");
        // 20 = 别人发布的社区文章（已终审发布）、21 = 官方资讯、22 = 发布过但随后删除的社区文章
        // publish_at 必须显式早于当前时刻：publishedSql 要求 publish_at<=CURRENT_TIMESTAMP，
        // 而 CURRENT_TIMESTAMP 写进 DATETIME 会被按秒取整，同一秒内回查可能"未来"几分之一秒，
        // 于是 requireReadable 偶发报「文章不存在或不可查看」（clean verify 上真踩到过）
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body,channel_code,publish_at,publish_status,community_managed) "
                + "VALUES(20,'社区文章','摘要','正文',4,DATEADD('SECOND',-2,CURRENT_TIMESTAMP),1,1)");
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body,channel_code,publish_at,publish_status,community_managed,is_deleted) "
                + "VALUES(21,'官方资讯','摘要','正文',1,DATEADD('SECOND',-2,CURRENT_TIMESTAMP),1,0,0)");
        // 删除一致性约束 chk_cn_del_consistent：is_deleted=1 必须带 deleted_at
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body,channel_code,publish_at,publish_status,community_managed,is_deleted,deleted_at) "
                + "VALUES(22,'被删掉的文章','摘要','正文',4,DATEADD('SECOND',-2,CURRENT_TIMESTAMP),1,1,1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO community_article(news_id,author_user_id,state,revision,ai_verdict) VALUES(20,3,'PUBLISHED',1,'PASS')");
        jdbc.update("INSERT INTO community_article(news_id,author_user_id,state,revision,ai_verdict) VALUES(22,3,'PUBLISHED',1,'PASS')");

        service = new CommunityService(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            jdbc.execute("DROP ALL OBJECTS");
        } finally {
            migrationConnection.close();
        }
    }

    private long comment(long newsId, long userId, String content, String key) {
        jdbc.update("INSERT INTO community_comment(news_id,user_id,content,client_request_id) VALUES(?,?,?,?)",
                newsId, userId, content, key);
        return jdbc.queryForObject("SELECT id FROM community_comment WHERE client_request_id=?", Long.class, key);
    }

    @Test
    void 只列出我自己的评论且新的在前() {
        long first = comment(20, 2, "我的第一条评论", "m-1");
        long second = comment(21, 2, "我的第二条评论", "m-2");
        comment(20, 3, "别人的评论不该出现", "o-1");

        var page = service.myComments(2, new PageQuery());

        assertEquals(2, page.getTotal(), "只统计自己的评论");
        assertEquals(List.of(Long.toString(second), Long.toString(first)),
                page.getRecords().stream().map(CommunityDtos.MyComment::id).toList(),
                "新的在前");
        var item = page.getRecords().get(1);
        assertEquals(20, item.newsId());
        assertEquals("社区文章", item.articleTitle());
        assertTrue(item.articleAvailable());
        assertFalse(item.hidden());
        assertNull(item.rootCommentId(), "根评论没有上级");
        assertNotNull(item.createdAt());
    }

    @Test
    void 被AI隐藏的评论我仍然看得到但会被标记() {
        long hidden = comment(20, 2, "加微信领降糖神药", "h-1");
        jdbc.update("UPDATE community_comment SET is_hidden=1,ai_verdict='REJECT',ai_status='DONE' WHERE id=?", hidden);

        assertEquals(0, service.comments(2, 20, new PageQuery()).getRecords().size(),
                "前提：公开列表里已经看不到它");
        var page = service.myComments(2, new PageQuery());

        assertEquals(1, page.getTotal(), "作者自己必须看得到，否则只会以为「发了却没有」");
        var item = page.getRecords().getFirst();
        assertTrue(item.hidden(), "标出来，前端据此显示「已被系统隐藏」");
        assertEquals("加微信领降糖神药", item.content());
    }

    @Test
    void 自己删除的评论不再出现() {
        long id = comment(20, 2, "这条我删了", "d-1");
        jdbc.update("UPDATE community_comment SET is_deleted=1 WHERE id=?", id);

        assertEquals(0, service.myComments(2, new PageQuery()).getTotal(),
                "用户主动删除的评论不该出现在「我发过的」里");
    }

    @Test
    void 文章删除后评论记录仍在但标记为不可查看() {
        long id = comment(22, 2, "文章后来被删了", "a-2");

        var page = service.myComments(2, new PageQuery());

        assertEquals(1, page.getTotal(), "文章没了，评论仍是用户的历史，不该整条消失");
        var item = page.getRecords().getFirst();
        assertEquals(Long.toString(id), item.id());
        assertFalse(item.articleAvailable(), "前端据此禁止跳转（跳过去只会 404）");
        assertEquals("被删掉的文章", item.articleTitle());
    }

    @Test
    void 回复与管理端计数一并返回() {
        long root = comment(20, 2, "我的根评论", "r-1");
        long myReply = comment(20, 2, "我自己回复自己", "r-2");
        jdbc.update("UPDATE community_comment SET root_comment_id=?,reply_to_comment_id=? WHERE id=?", root, root, myReply);
        long otherReply = comment(20, 3, "别人回复我", "r-3");
        jdbc.update("UPDATE community_comment SET root_comment_id=?,reply_to_comment_id=? WHERE id=?", root, root, otherReply);
        jdbc.update("INSERT INTO community_comment_like(user_id,comment_id,liked,client_request_id) VALUES(3,?,1,'lk-1')", root);

        var page = service.myComments(2, new PageQuery());

        assertEquals(2, page.getTotal(), "回复也是我发的评论");
        var reply = page.getRecords().getFirst();
        assertEquals(Long.toString(myReply), reply.id());
        assertEquals(Long.toString(root), reply.rootCommentId(), "回复带上所属根评论，前端才能定位");
        assertEquals(0, reply.replyCount());
        var rootItem = page.getRecords().get(1);
        assertEquals(2, rootItem.replyCount(), "根评论返回回复数（不含隐藏/删除）");
        assertEquals(1, rootItem.likeCount());
    }

    @Test
    void 分页参数需要校验() {
        comment(20, 2, "某条评论", "p-1");
        PageQuery tooBig = new PageQuery();
        tooBig.setPageSize(51);

        assertThrows(BizException.class, () -> service.myComments(2, tooBig), "每页上限 50，与社区其它列表一致");
    }
}
