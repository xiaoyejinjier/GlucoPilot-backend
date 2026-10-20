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
}
