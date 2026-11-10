package com.threadfit.glucopilot.community.shared.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.community.shared.mapper.CommunityAccountCleanupMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

class CommunityAccountCleanupServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private CommunityAccountCleanupService service;
    private final Map<String, String> privateTables =
            Map.of(
                    "user_reminder_read", "user_id",
                    "community_follow", "follower_id",
                    "community_article_reader", "user_id",
                    "community_article_view", "user_id",
                    "community_article_hidden", "user_id",
                    "community_article_draft", "user_id",
                    "community_notification", "recipient_id",
                    "community_report", "reporter_id");

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:cleanup_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (var table : privateTables.entrySet()) {
            jdbc.execute("CREATE TABLE " + table.getKey() + " (" + table.getValue() + " BIGINT)");
            jdbc.update("INSERT INTO " + table.getKey() + " VALUES(1),(2)");
        }
        jdbc.execute(
                "CREATE TABLE community_blogger (id BIGINT PRIMARY KEY,user_id BIGINT,"
                        + "nickname VARCHAR(64),avatar_url VARCHAR(200),bio VARCHAR(200))");
        jdbc.update(
                "INSERT INTO community_blogger VALUES(77,1,'author','avatar','bio'),"
                        + "(78,2,'other','other-avatar','other-bio')");
        jdbc.execute("CREATE TABLE cms_news (id BIGINT PRIMARY KEY,blogger_id BIGINT)");
        jdbc.update("INSERT INTO cms_news VALUES(90,77)");
        for (String table :
                new String[] {
                    "community_message",
                    "community_comment",
                    "community_comment_like",
                    "news_user_action"
                }) {
            jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
            jdbc.update("INSERT INTO " + table + " VALUES(1),(2)");
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(CommunityAccountCleanupMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var sessions = new SqlSessionTemplate(factory.getObject());
        service =
                new CommunityAccountCleanupService(
                        sessions.getMapper(CommunityAccountCleanupMapper.class));
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void repeatedCleanupRemovesOnlyOwnerPreferencesAndPreservesSharedHistory() {
        tx.executeWithoutResult(status -> service.purgeAccountData(1));
        tx.executeWithoutResult(status -> service.purgeAccountData(1));
        for (var table : privateTables.entrySet()) {
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM "
                                    + table.getKey()
                                    + " WHERE "
                                    + table.getValue()
                                    + "=1",
                            Integer.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM "
                                    + table.getKey()
                                    + " WHERE "
                                    + table.getValue()
                                    + "=2",
                            Integer.class));
        }
        assertNull(
                jdbc.queryForObject(
                        "SELECT user_id FROM community_blogger WHERE id=77", Long.class));
        assertEquals(
                "已注销用户",
                jdbc.queryForObject(
                        "SELECT nickname FROM community_blogger WHERE id=77", String.class));
        assertNull(
                jdbc.queryForObject(
                        "SELECT avatar_url FROM community_blogger WHERE id=77", String.class));
        assertEquals(
                "other",
                jdbc.queryForObject(
                        "SELECT nickname FROM community_blogger WHERE id=78", String.class));
        assertEquals(
                77L,
                jdbc.queryForObject("SELECT blogger_id FROM cms_news WHERE id=90", Long.class));
        for (String table :
                new String[] {
                    "community_message",
                    "community_comment",
                    "community_comment_like",
                    "news_user_action"
                }) {
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
    }
}
