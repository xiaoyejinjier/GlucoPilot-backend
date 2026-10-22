package com.threadfit.glucopilot.community;
import java.sql.Connection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
public final class CommunityTestSchema {
    private CommunityTestSchema(){}
    public static void applyDraftMigration(Connection connection) throws Exception {
        try(var s=connection.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS data_export_task(id BIGINT PRIMARY KEY,user_id BIGINT)");
            s.execute("CREATE TABLE IF NOT EXISTS user_profile(user_id BIGINT PRIMARY KEY,nickname VARCHAR(64),avatar_url VARCHAR(512),is_deleted INT DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS community_article(news_id BIGINT PRIMARY KEY,state VARCHAR(24),author_user_id BIGINT)");
            s.execute("CREATE TABLE IF NOT EXISTS community_follow(follower_id BIGINT,target_key VARCHAR(32),following INT,PRIMARY KEY(follower_id,target_key))");
        }
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V34__drafts_notifications_exports.sql"));migration.setSqlScriptEncoding("UTF-8");migration.populate(connection);
        try(var s=connection.createStatement()) {
            s.execute("ALTER TABLE community_article_draft ADD COLUMN video_ids TEXT DEFAULT '[]' NOT NULL");
            s.execute("CREATE TABLE IF NOT EXISTS community_report(id BIGINT AUTO_INCREMENT PRIMARY KEY,reporter_id BIGINT,target_type VARCHAR(16),target_id BIGINT,reason VARCHAR(32),details VARCHAR(500),state VARCHAR(16),resolution VARCHAR(32),resolution_note VARCHAR(1000),created_at TIMESTAMP,resolved_at TIMESTAMP)");
        }
        var feedback=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V36__community_report_feedback.sql"));
        feedback.setSqlScriptEncoding("UTF-8");feedback.populate(connection);
        var access=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V37__community_visibility_views_preferences.sql"));
        access.setSqlScriptEncoding("UTF-8");access.populate(connection);
        try(var tables=connection.getMetaData().getTables(null,null,"media_file",null)) {
            if(tables.next())try(var s=connection.createStatement()) {
                s.execute("ALTER TABLE media_file DROP CONSTRAINT IF EXISTS chk_mf_biz");
                s.execute("ALTER TABLE media_file ADD CONSTRAINT chk_mf_biz CHECK (biz_type IN (1,2,3,4,5,6,7,8))");
            }
        }
    }
}
