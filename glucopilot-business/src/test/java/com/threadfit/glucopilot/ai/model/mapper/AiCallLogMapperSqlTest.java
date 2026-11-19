package com.threadfit.glucopilot.ai.model.mapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账单表的聚合与清理 SQL（H2 真库执行）。
 *
 * <h2>为什么必须真跑 SQL</h2>
 * 这段 SQL 的语义全在库里：{@code CAST(gmt_create AS DATE)} 分天、{@code COALESCE} 归一化空场景、
 * {@code SUM(CASE WHEN ok = 0 ...)} 数失败、{@code DELETE ... LIMIT} 分批。mock 掉 mapper 等于
 * 什么都没验证——而这里最容易出的错是<b>方言差异</b>（{@code DAY} 在 H2 是区间关键字、MySQL 不是），
 * 这种错只会在测试库或生产库其中一边炸。
 */
class AiCallLogMapperSqlTest {

    private static final String DDL = """
            CREATE TABLE ai_call_log (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              scene_code VARCHAR(32) NOT NULL DEFAULT '',
              user_id BIGINT NULL,
              session_id BIGINT NULL,
              channel VARCHAR(16) NULL,
              provider_code INT NULL,
              model_code VARCHAR(64) NULL,
              model_endpoint VARCHAR(128) NULL,
              prompt_tokens INT NULL,
              completion_tokens INT NULL,
              cache_hit_tokens INT NULL,
              cache_miss_tokens INT NULL,
              latency_ms INT NULL,
              stream TINYINT NULL,
              ok TINYINT NULL,
              error_code VARCHAR(64) NULL,
              gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))
            """;

    private static Configuration configuration(UnpooledDataSource datasource) {
        Configuration configuration =
                new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiCallLogMapper.class);
        return configuration;
    }

    /** 行数用裸 JDBC 数：{@code BaseMapper.selectCount} 依赖 MyBatis-Plus 的 SQL 注入器，纯 MyBatis 配置里没有。 */
    private static long count(UnpooledDataSource datasource) throws Exception {
        try (var connection = datasource.getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM ai_call_log")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static UnpooledDataSource datasource(String prefix) {
        return new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
    }

    @Test
    void dailyUsageGroupsByDaySceneAndModel() throws Exception {
        UnpooledDataSource datasource = datasource("acl_usage");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute(DDL);
            sql.execute("INSERT INTO ai_call_log (scene_code, model_code, prompt_tokens, completion_tokens, "
                    + "cache_hit_tokens, cache_miss_tokens, ok, gmt_create) VALUES "
                    // 同一天、同场景、同模型：两次成功调用，命中/未命中各自累加
                    + "('CHAT','deepseek-v4-pro',1000,200,800,200,1,'2026-11-17 09:00:00'),"
                    + "('CHAT','deepseek-v4-pro',500,100,500,0,1,'2026-11-17 10:00:00'),"
                    // 同一天但换了场景：必须单独一行
                    + "('SUMMARY','deepseek-v4-flash',300,50,NULL,NULL,1,'2026-11-17 11:00:00'),"
                    // 失败调用：计入失败数，用量照算（很多厂商对超时也计费）
                    + "('CHAT','deepseek-v4-pro',100,0,NULL,NULL,0,'2026-11-17 12:00:00'),"
                    // 空场景 + 空模型：归到 '' 行，不能丢
                    + "('',NULL,40,10,NULL,NULL,1,'2026-11-17 13:00:00'),"
                    // 区间外的行不能被聚合进来
                    + "('CHAT','deepseek-v4-pro',9999,9999,1,1,1,'2026-11-16 23:59:59')");
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration(datasource)).openSession()) {
            var mapper = session.getMapper(AiCallLogMapper.class);

            List<AiCallUsageRow> rows = mapper.dailyUsage(
                    LocalDateTime.of(2026, 11, 17, 0, 0), LocalDateTime.of(2026, 11, 18, 0, 0));

            assertThat(rows).hasSize(3);
            // ORDER BY day DESC, scene_code ASC：'' 排最前，然后 CHAT，最后 SUMMARY
            assertThat(rows.get(0).getSceneCode()).isEmpty();
            assertThat(rows.get(0).getModelCode()).isEmpty();
            assertThat(rows.get(0).getPromptTokens()).isEqualTo(40);

            AiCallUsageRow chat = rows.get(1);
            assertThat(chat.getUsageDay()).isEqualTo(java.time.LocalDate.of(2026, 11, 17));
            assertThat(chat.getSceneCode()).isEqualTo("CHAT");
            assertThat(chat.getPromptTokens()).isEqualTo(1600);
            assertThat(chat.getCompletionTokens()).isEqualTo(300);
            assertThat(chat.getCacheHitTokens()).isEqualTo(1300);
            assertThat(chat.getCacheMissTokens()).isEqualTo(200);
            assertThat(chat.getCalls()).isEqualTo(3);
            assertThat(chat.getFailedCalls()).isEqualTo(1);

            assertThat(rows.get(2).getSceneCode()).isEqualTo("SUMMARY");
            assertThat(rows.get(2).getCacheHitTokens()).isZero();
        }
    }

    @Test
    void deleteOlderThanOnlyRemovesExpiredRowsInOneBatch() throws Exception {
        UnpooledDataSource datasource = datasource("acl_cleanup");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute(DDL);
            sql.execute("INSERT INTO ai_call_log (scene_code, ok, gmt_create) VALUES "
                    + "('CHAT',1,'2026-01-01 00:00:00'),"
                    + "('CHAT',1,'2026-01-02 00:00:00'),"
                    + "('CHAT',1,'2026-01-03 00:00:00'),"
                    + "('CHAT',1,'2026-11-17 00:00:00')");
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration(datasource)).openSession(true)) {
            var mapper = session.getMapper(AiCallLogMapper.class);

            int deleted = mapper.deleteOlderThan(LocalDateTime.of(2026, 6, 1, 0, 0), 2);

            // LIMIT 生效：一次只删 2 条，交给调用方循环，避免一条 DELETE 长时间锁表
            assertThat(deleted).isEqualTo(2);
            assertThat(count(datasource)).isEqualTo(2);
        }
    }

    @Test
    void purgeByUserIdRemovesEveryRowOfThatUser() throws Exception {
        UnpooledDataSource datasource = datasource("acl_purge");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute(DDL);
            sql.execute("INSERT INTO ai_call_log (scene_code, user_id, ok) VALUES "
                    + "('CHAT',27,1),('SUMMARY',27,1),('CHAT',28,1)");
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration(datasource)).openSession(true)) {
            var mapper = session.getMapper(AiCallLogMapper.class);

            assertThat(mapper.purgeByUserId(27L)).isEqualTo(2);
            assertThat(count(datasource)).isEqualTo(1);
        }
    }
}
