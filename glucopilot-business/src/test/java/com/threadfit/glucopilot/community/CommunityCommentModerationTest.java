package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 评论审核（先发后审）在真实库上的行为。
 * <p>
 * 这些事 Mock 证明不了，必须落到 SQL：
 * <ol>
 *   <li><b>抢占</b>：{@code FOR UPDATE SKIP LOCKED} + 状态条件，同一轮只取一条、不重复审；</li>
 *   <li><b>命中即隐藏</b>：REJECT 后 {@code is_hidden=1}，公开列表立刻查不到（含回复与计数）；</li>
 *   <li><b>ERROR 不隐藏但也不算通过</b>：模型故障时不能让社区停摆，也不能当成干净内容；</li>
 *   <li><b>恢复</b>：管理端恢复只把 {@code is_hidden} 归零，<b>保留</b>原 {@code ai_verdict}
 *       （"机器判过违规"是发生过的事实，删掉就解释不清），并写一条 ADMIN/RESTORE 日志。</li>
 *   <li><b>主动隐藏</b>：管理端也能把机器放行的评论压下去（{@code ADMIN/HIDE} 日志），
 *       且人工隐藏不会被事后到达的机器 PASS 放开——机器能加严，不能替人放宽。</li>
 * </ol>
 * 另外验证 V43 迁移的两步默认值意图：历史评论是 {@code SKIPPED}（不会被一次性拉去重审），
 * 新插入的评论是 {@code PENDING}（自动进审核队列）。
 */
class CommunityCommentModerationTest {

    private JdbcTemplate jdbc;
    private Connection migrationConnection;
    private CommunityModerationQueue queue;
    private CommunityService community;
    private CommunityPublicationService publication;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:comment_moderation_" + UUID.randomUUID()
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
                "V32__community_comment_replies.sql", "V33__community_article_images.sql")) {
            // V33 里有 MySQL 的 DROP CHECK，H2 要换成 DROP CONSTRAINT（与既有社区测试同一处理）
            String script = new ClassPathResource("db/migration/" + file)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("DROP CHECK chk_mf_biz", "DROP CONSTRAINT chk_mf_biz");
            ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                    new org.springframework.core.io.ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
            migration.setSqlScriptEncoding("UTF-8");
            migration.populate(migrationConnection);
        }
        CommunityTestSchema.applyDraftMigration(migrationConnection);
        // applyDraftMigration 已经顺带跑了 V37（举报反馈）与 V38（可见性），
        // 评论读取要经过的 ArticleAccessService 依赖那几张表，因此不需要在这里重复建。
        CommunityTestSchema.applyModerationMigration(migrationConnection);

        jdbc.update("INSERT INTO user_account(id) VALUES(2),(3)");
        jdbc.update("INSERT INTO user_profile(user_id,nickname) VALUES(2,'作者'),(3,'评论者')");
        jdbc.update("INSERT INTO cms_news(id,title,summary_text,content_body,channel_code,publish_at,publish_status,community_managed) "
                // publish_at 提前 2 秒：CURRENT_TIMESTAMP 落库按秒取整，同秒回查可能"未来"几分之一秒，
                // requireReadable 的 publish_at<=CURRENT_TIMESTAMP 会偶发不成立
                + "VALUES(20,'社区文章','摘要','正文',4,DATEADD('SECOND',-2,CURRENT_TIMESTAMP),1,1)");
        // 评论读取要先过 ArticleAccessService.requireReadable：社区文章必须处于 PUBLISHED，
        // 且 author 是有效账号（user_account 2），否则一律 404「文章不存在或不可查看」。
        jdbc.update("INSERT INTO community_article(news_id,author_user_id,state,revision,ai_verdict) "
                + "VALUES(20,2,'PUBLISHED',1,'PASS')");

        var publication = new CommunityPublicationService(jdbc, mock(CmsNewsMapper.class), new CommunityService(jdbc));
        this.publication = publication;
        community = new CommunityService(jdbc);
        queue = new CommunityModerationQueue(jdbc, new DataSourceTransactionManager(Objects.requireNonNull(ds)),
                publication);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            jdbc.execute("DROP ALL OBJECTS");
        } finally {
            migrationConnection.close();
        }
    }

    /** 直接插一条评论（模拟线上 addComment 的列，不含 ai_status → 走列默认值）。 */
    private long insertComment(String content, String clientRequestId) {
        jdbc.update("INSERT INTO community_comment(news_id,user_id,content,client_request_id) VALUES(20,3,?,?)",
                content, clientRequestId);
        return jdbc.queryForObject("SELECT id FROM community_comment WHERE client_request_id=?", Long.class,
                clientRequestId);
    }

    private ModerationVerdict reject(String code, int severity, int confidence) {
        ModerationCategories category = ModerationCategories.of(code).orElseThrow();
        return new ModerationVerdict(ModerationVerdict.REJECT, category, severity, confidence,
                "命中" + category.displayName(),
                List.of(new ModerationVerdict.Hit(category, severity, confidence, "")));
    }

    // ---------------------------------------------------------------- 队列

    @Test
    void 新评论进队列且一次只抢占一条() {
        long first = insertComment("晚饭后散步半小时，血糖挺稳", "c-1");
        insertComment("今天的餐后有点高，想问问怎么调整", "c-2");

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT ai_status FROM community_comment WHERE id=?", String.class, first),
                "新插入的评论默认待审（V43 把列默认值改成 PENDING）");

        var job = queue.claimComment();
        assertNotNull(job);
        assertEquals(CommunityModerationQueue.COMMENT, job.targetType());
        assertTrue(job.isComment());
        assertEquals("REVIEWING", jdbc.queryForObject(
                "SELECT ai_status FROM community_comment WHERE id=?", String.class, job.id()),
                "被抢走后置为 REVIEWING，避免被第二个消费者重复审");

        var second = queue.claimComment();
        assertNotNull(second);
        assertTrue(second.id() != job.id(), "同一轮不会取到同一条");
    }

    @Test
    void 僵尸评论被回收成待审而不是判错() {
        long id = insertComment("某条评论", "c-stale");
        jdbc.update("UPDATE community_comment SET ai_status='REVIEWING',ai_claimed_at=? WHERE id=?",
                java.time.LocalDateTime.now().minusMinutes(10), id);

        var job = queue.claimComment();

        assertNotNull(job);
        assertEquals(id, job.id(), "超时的 REVIEWING 会被收回并重新排队（评论没有人工重试按钮）");
    }

    @Test
    void 历史评论不会被一次性拉去重审() {
        // 模拟 V43 上线前的存量评论：迁移把它们的 ai_status 落成 SKIPPED
        long id = insertComment("存量评论", "c-legacy");
        jdbc.update("UPDATE community_comment SET ai_status='SKIPPED' WHERE id=?", id);

        assertNull(queue.claimComment(), "存量评论不在 PENDING 队列里，避免上线当天全量重审");
    }

    // ---------------------------------------------------------------- 结论写回

    @Test
    void 判违规的评论立即隐藏并从公开列表消失() {
        long id = insertComment("加我微信，包治糖尿病不用吃药", "c-spam");
        var job = queue.claimComment();
        assertNotNull(job);

        assertTrue(queue.finish(job, reject("medical_claim", 3, 96), "deepseek-v4-pro", "moderation@v1"));

        var row = jdbc.queryForMap("SELECT ai_verdict,ai_category,ai_severity,ai_prompt_ver,is_hidden "
                + "FROM community_comment WHERE id=?", id);
        assertEquals("REJECT", row.get("ai_verdict"));
        assertEquals("medical_claim", row.get("ai_category"));
        assertEquals(3, ((Number) row.get("ai_severity")).intValue());
        assertEquals("moderation@v1", row.get("ai_prompt_ver"));
        assertEquals(1, ((Number) row.get("is_hidden")).intValue(), "先发后审：命中即隐藏");

        assertEquals(0, community.comments(2, 20, new PageQuery()).getRecords().size(),
                "公开列表查不到被隐藏的评论");
        assertEquals(0, community.commentCount(20), "计数也要排除隐藏评论");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM community_moderation_hit "
                + "WHERE target_type='COMMENT' AND target_id=? AND category_code='medical_claim'", Integer.class, id),
                "命中证据要落表（只记元数据）");
        assertEquals("REJECT", jdbc.queryForObject("SELECT decision FROM community_moderation_log "
                + "WHERE target_type='COMMENT' AND target_id=?", String.class, id));
    }

    @Test
    void 判通过的评论保持可见() {
        long id = insertComment("我坚持记录饮食三个月，糖化从 8.1 降到 7.2", "c-ok");
        var job = queue.claimComment();

        assertTrue(queue.finish(job, ModerationVerdict.pass("个人经历分享，无普适化承诺"),
                "deepseek-v4-pro", "moderation@v1"));

        var row = jdbc.queryForMap("SELECT ai_verdict,is_hidden,ai_category FROM community_comment WHERE id=?", id);
        assertEquals("PASS", row.get("ai_verdict"));
        assertEquals(0, ((Number) row.get("is_hidden")).intValue());
        assertNull(row.get("ai_category"), "PASS 不写类别，才能区分「没审」与「审过且干净」");
        assertEquals(1, community.comments(2, 20, new PageQuery()).getRecords().size());
    }

    @Test
    void 审核失败时评论保持可见但不算通过() {
        long id = insertComment("一句普通评论", "c-error");
        var job = queue.claimComment();

        assertTrue(queue.finish(job, ModerationVerdict.error("模型不可用"), "", "moderation@v1"));

        var row = jdbc.queryForMap("SELECT ai_status,ai_verdict,is_hidden FROM community_comment WHERE id=?", id);
        assertEquals("DONE", row.get("ai_status"));
        assertEquals("ERROR", row.get("ai_verdict"), "失败不能写成 PASS");
        assertEquals(0, ((Number) row.get("is_hidden")).intValue(),
                "模型故障时不隐藏（否则社区停摆），但也不等于通过");
        assertEquals(1, community.comments(2, 20, new PageQuery()).getRecords().size());
    }

    @Test
    void 重复写回只生效一次() {
        insertComment("某条评论", "c-twice");
        var job = queue.claimComment();
        assertTrue(queue.finish(job, ModerationVerdict.pass("ok"), "m", "moderation@v1"));

        assertFalse(queue.finish(job, reject("porn", 3, 99), "m", "moderation@v1"),
                "状态已不是 REVIEWING，重复结果影响 0 行");
    }

    // ---------------------------------------------------------------- 恢复

    @Test
    void 恢复接口把误杀评论放回可见且保留原判定() {
        long id = insertComment("二甲双胍应该什么时候吃", "c-misfire");
        var job = queue.claimComment();
        assertTrue(queue.finish(job, reject("medical_advice", 3, 88), "m", "moderation@v1"));
        assertEquals(0, community.comments(2, 20, new PageQuery()).getRecords().size());

        assertTrue(queue.restoreComment(99L, id));

        assertEquals(1, community.comments(2, 20, new PageQuery()).getRecords().size(), "恢复后重新可见");
        var row = jdbc.queryForMap("SELECT is_hidden,ai_verdict FROM community_comment WHERE id=?", id);
        assertEquals(0, ((Number) row.get("is_hidden")).intValue());
        assertEquals("REJECT", row.get("ai_verdict"),
                "保留原判定：机器判过违规是事实，删掉就解释不清为什么被隐藏过");
        assertEquals("RESTORE", jdbc.queryForObject("SELECT decision FROM community_moderation_log "
                + "WHERE target_type='COMMENT' AND target_id=? AND actor_type='ADMIN'", String.class, id));
    }

    @Test
    void 恢复一条本来就没隐藏的评论是幂等的() {
        long id = insertComment("普通评论", "c-idempotent");

        assertFalse(queue.restoreComment(99L, id), "没有隐藏过就没什么可恢复，返回 false 但不报错");
    }

    // ---------------------------------------------------------------- 主动隐藏

    @Test
    void 管理员可以主动隐藏一条机器判定通过的评论() {
        long id = insertComment("这家医院我是不推荐的，态度很差", "c-admin-hide");
        assertTrue(queue.finish(queue.claimComment(), ModerationVerdict.pass("ok"), "m", "moderation@v1"));
        assertEquals(1, community.comments(2, 20, new PageQuery()).getRecords().size(), "前提：机器放行、公开可见");

        assertTrue(queue.setCommentHidden(99L, id, true));

        assertEquals(0, community.comments(2, 20, new PageQuery()).getRecords().size(), "隐藏后公开列表立刻查不到");
        var row = jdbc.queryForMap("SELECT is_hidden,ai_verdict,ai_status FROM community_comment WHERE id=?", id);
        assertEquals(1, ((Number) row.get("is_hidden")).intValue());
        assertEquals("PASS", row.get("ai_verdict"), "人工否决不改写机器结论：两边的事实都要留着");
        assertEquals("DONE", row.get("ai_status"), "AI 已经审完的状态不能因为人工隐藏而倒退");
        assertEquals("HIDE", jdbc.queryForObject("SELECT decision FROM community_moderation_log "
                + "WHERE target_type='COMMENT' AND target_id=? AND actor_type='ADMIN'", String.class, id),
                "人工隐藏必须留痕，否则事后解释不清是谁、按什么理由压的");
    }

    @Test
    void 隐藏之后还能再次恢复显示() {
        long id = insertComment("我每天走八千步，血糖平稳", "c-admin-roundtrip");

        assertTrue(queue.setCommentHidden(99L, id, true));
        assertEquals(0, community.comments(2, 20, new PageQuery()).getRecords().size());

        assertTrue(queue.setCommentHidden(99L, id, false), "恢复显示走同一个入口，方向和 AI 判定无关");
        assertEquals(1, community.comments(2, 20, new PageQuery()).getRecords().size(), "恢复后重新可见");
        assertEquals(List.of("HIDE", "RESTORE"), jdbc.queryForList("SELECT decision FROM community_moderation_log "
                + "WHERE target_type='COMMENT' AND target_id=? ORDER BY id", String.class, id));
    }

    @Test
    void 重复隐藏或重复恢复都幂等且不重复写日志() {
        long id = insertComment("普通评论", "c-admin-idem");
        assertTrue(queue.setCommentHidden(99L, id, true));

        assertFalse(queue.setCommentHidden(99L, id, true), "已经是隐藏状态，没有变化就返回 false");
        assertTrue(queue.restoreComment(99L, id), "历史的恢复入口仍然可用");
        assertFalse(queue.setCommentHidden(99L, id, false), "已经可见，再恢复也没有变化");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM community_moderation_log "
                + "WHERE target_type='COMMENT' AND target_id=?", Integer.class, id),
                "只有真实的状态变化才写日志，运营重复点击不该刷出一堆记录");
    }

    @Test
    void 隐藏不存在或已删除的评论明确报404() {
        var missing = assertThrows(BizException.class, () -> queue.setCommentHidden(99L, 999_999L, true));
        assertEquals("评论不存在或已删除", missing.getMessage());

        long deleted = insertComment("已删除的评论", "c-admin-deleted");
        jdbc.update("UPDATE community_comment SET is_deleted=1 WHERE id=?", deleted);
        assertThrows(BizException.class, () -> queue.setCommentHidden(99L, deleted, true),
                "逻辑删除的评论不该还能被隐藏——那只会制造一条谁都看不到的隐藏记录");
        assertFalse(queue.restoreComment(99L, deleted), "历史恢复入口保持幂等语义：查不到就是没变化");
    }

    @Test
    void 人工先隐藏的评论不会被事后到达的机器PASS放开() {
        long id = insertComment("擦边但没到违规线的内容", "c-admin-race");
        assertTrue(queue.setCommentHidden(99L, id, true), "评论还在队列里，运营先手动压下去");

        assertTrue(queue.finish(queue.claimComment(), ModerationVerdict.pass("ok"), "m", "moderation@v1"));

        assertEquals(1, jdbc.queryForObject("SELECT is_hidden FROM community_comment WHERE id=?", Integer.class, id),
                "机器能加严不能放宽：PASS 不能把人工隐藏悄悄推翻");
        assertEquals(0, community.comments(2, 20, new PageQuery()).getRecords().size());
        assertEquals("PASS", jdbc.queryForObject("SELECT ai_verdict FROM community_comment WHERE id=?", String.class, id),
                "机器结论照常记录，只是没有覆盖人工决定");
    }

    // ---------------------------------------------------------------- 管理端列表

    @Test
    void 管理端列表能查到被隐藏的评论并带上判定依据() {
        long id = insertComment("包治糖尿病，停药三天见效", "c-admin-1");
        var job = queue.claimComment();
        assertTrue(queue.finish(job, reject("medical_claim", 3, 95), "deepseek-v4-pro", "moderation@v1"));

        assertEquals(0, community.commentCount(20), "前提：公开侧已经看不到它");
        var page = publication.adminComments(null, null, 1, null, new PageQuery());

        assertEquals(1, page.getTotal(), "管理端必须能看到被隐藏的评论，否则运营找不出误判");
        var item = page.getRecords().getFirst();
        assertEquals(Long.toString(id), item.id());
        assertEquals(20, item.newsId());
        assertEquals("社区文章", item.articleTitle(), "列表要能定位到是哪篇文章下的评论");
        assertEquals("包治糖尿病，停药三天见效", item.content(), "管理员必须读到原文才能判断是否误杀");
        assertEquals("u3", item.authorKey());
        assertEquals("评论者", item.authorNickname());
        assertEquals("DONE", item.aiStatus());
        assertEquals("REJECT", item.aiVerdict());
        assertEquals("medical_claim", item.aiCategory());
        assertEquals(3, item.aiSeverity().intValue());
        assertEquals(95, item.aiConfidence().intValue());
        assertEquals("deepseek-v4-pro", item.aiModel());
        assertEquals("moderation@v1", item.aiPromptVer(), "带上提示词版本才能复现当时的判定");
        assertTrue(item.hidden());
        assertNotNull(item.createdAt());
    }

    @Test
    void 管理端列表可按审核结论与关键词筛选() {
        long spam = insertComment("加微信领降糖神药", "c-admin-spam");
        insertComment("我每天走八千步，血糖平稳", "c-admin-ok");
        assertTrue(queue.finish(queue.claimComment(), reject("illegal_goods", 2, 80), "m", "moderation@v1"));
        assertTrue(queue.finish(queue.claimComment(), ModerationVerdict.pass("个人经验分享"), "m", "moderation@v1"));

        assertEquals(2, publication.adminComments(null, null, null, null, new PageQuery()).getTotal());
        assertEquals(1, publication.adminComments("DONE", "REJECT", null, null, new PageQuery()).getTotal());
        assertEquals(1, publication.adminComments(null, "PASS", null, null, new PageQuery()).getTotal());
        assertEquals(0, publication.adminComments(null, "ERROR", null, null, new PageQuery()).getTotal(),
                "ERROR 要能被单独筛出来复查：既没通过也不该被隐藏");
        assertEquals(1, publication.adminComments(null, null, 1, null, new PageQuery()).getTotal());
        assertEquals(1, publication.adminComments(null, null, 0, null, new PageQuery()).getTotal());
        assertEquals(1, publication.adminComments(null, null, null, "降糖", new PageQuery()).getTotal(),
                "关键词命中评论正文");
        assertEquals(2, publication.adminComments(null, null, null, "评论者", new PageQuery()).getTotal(),
                "关键词命中作者昵称");
        assertEquals(Long.toString(spam), String.valueOf(
                publication.adminComments(null, "REJECT", null, null, new PageQuery()).getRecords().getFirst().id()));
    }

    @Test
    void 管理端列表排除已删除评论并接受参数校验() {
        long deleted = insertComment("会被删除的评论", "c-admin-del");
        long kept = insertComment("保留下来的评论", "c-admin-keep");
        jdbc.update("UPDATE community_comment SET is_deleted=1 WHERE id=?", deleted);

        var page = publication.adminComments(null, null, null, null, new PageQuery());
        assertEquals(1, page.getTotal(), "逻辑删除的评论不该出现在审核列表里");
        assertEquals(Long.toString(kept), page.getRecords().getFirst().id());

        assertThrows(BizException.class,
                () -> publication.adminComments("WHATEVER", null, null, null, new PageQuery()), "未知审核状态直接拒绝");
        assertThrows(BizException.class,
                () -> publication.adminComments(null, "MAYBE", null, null, new PageQuery()), "未知判定同样拒绝");
        assertThrows(BizException.class,
                () -> publication.adminComments(null, null, 2, null, new PageQuery()), "hidden 只接受 0/1");
        assertThrows(BizException.class,
                () -> publication.adminComments(null, null, null, "字".repeat(81), new PageQuery()), "搜索词上限 80 字");
    }
}
