package com.threadfit.glucopilot.community;
import java.sql.Connection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
public final class CommunityTestSchema {
    private CommunityTestSchema(){}
    public static void applyDraftMigration(Connection connection) throws Exception {
        try(var s=connection.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS data_export_task(id BIGINT PRIMARY KEY,user_id BIGINT)");}
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V34__drafts_notifications_exports.sql"));migration.setSqlScriptEncoding("UTF-8");migration.populate(connection);
        try(var s=connection.createStatement()) {
            s.execute("ALTER TABLE community_article_draft ADD COLUMN video_ids TEXT DEFAULT '[]' NOT NULL");
        }
        try(var tables=connection.getMetaData().getTables(null,null,"media_file",null)) {
            if(tables.next())try(var s=connection.createStatement()) {
                s.execute("ALTER TABLE media_file DROP CONSTRAINT IF EXISTS chk_mf_biz");
                s.execute("ALTER TABLE media_file ADD CONSTRAINT chk_mf_biz CHECK (biz_type IN (1,2,3,4,5,6,7,8))");
            }
        }
    }

    /**
     * V37（社区 AI 审核）的 H2 版结构。
     * <p>
     * 为什么不直接跑 {@code V37__community_moderation_ai.sql}：那份迁移含 MySQL 专有语法
     * （{@code MODIFY COLUMN}）并会播种 {@code ai_prompt_template}，而社区这几个测试用 H2
     * 且不建 AI 侧的表。这里只补出<b>被测代码真正会读写的列/表</b>，
     * 与既有 {@link #applyDraftMigration}（补 V34 的列）是同一套做法。
     * <p>
     * 与线上 V37 的两处差异：① {@code ai_status} 默认值一步写 'PENDING'（线上分两步，先把存量评论
     * 标成 'SKIPPED' 再改默认值；H2 测试库没有存量数据）；② 不播种审核提示词。
     */
    public static void applyModerationMigration(Connection connection) throws Exception {
        try (var s = connection.createStatement()) {
            // 有些社区测试只建"互动"那几张表（V29），没有 community_article：按需补，缺表就跳过，
            // 这样同一个入口既能服务文章测试也能服务评论测试。
            if (tableExists(connection, "community_article")) {
                s.execute("ALTER TABLE community_article ADD COLUMN ai_category VARCHAR(32) NULL");
                s.execute("ALTER TABLE community_article ADD COLUMN ai_severity TINYINT NULL");
                s.execute("ALTER TABLE community_article ADD COLUMN ai_confidence TINYINT NULL");
                s.execute("ALTER TABLE community_article ADD COLUMN ai_prompt_ver VARCHAR(32) NULL");
            }

            s.execute("ALTER TABLE community_comment ADD COLUMN ai_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_verdict VARCHAR(16) NOT NULL DEFAULT ''");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_category VARCHAR(32) NULL");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_severity TINYINT NULL");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_confidence TINYINT NULL");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_reason VARCHAR(1000) NOT NULL DEFAULT ''");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_model VARCHAR(128) NOT NULL DEFAULT ''");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_prompt_ver VARCHAR(32) NULL");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_claimed_at TIMESTAMP(3) NULL");
            s.execute("ALTER TABLE community_comment ADD COLUMN ai_checked_at TIMESTAMP(3) NULL");
            s.execute("ALTER TABLE community_comment ADD COLUMN is_hidden TINYINT NOT NULL DEFAULT 0");
            s.execute("CREATE INDEX idx_cc_ai_queue ON community_comment(ai_status, id)");

            s.execute("CREATE TABLE community_moderation_hit ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "target_type VARCHAR(16) NOT NULL,"
                    + "target_id BIGINT NOT NULL,"
                    + "revision INT NULL,"
                    + "hit_source VARCHAR(16) NOT NULL DEFAULT 'AI',"
                    + "category_code VARCHAR(32) NOT NULL,"
                    + "severity TINYINT NOT NULL,"
                    + "confidence TINYINT NULL,"
                    + "start_offset INT NULL,"
                    + "end_offset INT NULL,"
                    + "snippet_hash CHAR(64) NULL,"
                    + "snippet_len INT NULL,"
                    + "reason VARCHAR(500) NOT NULL DEFAULT '',"
                    + "model_code VARCHAR(128) NOT NULL DEFAULT '',"
                    + "prompt_ver VARCHAR(32) NULL,"
                    + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                    + "CONSTRAINT chk_cmh_severity CHECK (severity BETWEEN 1 AND 3))");
        }
    }

    /** 表是否存在（H2/MySQL 通用，用 JDBC 元数据而不是查 information_schema）。 */
    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        }
    }
}
