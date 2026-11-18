package com.threadfit.glucopilot.ai.business.chat.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话记忆回放相关的两条 SQL（真库 H2）：
 * <ol>
 *   <li>{@code selectRecentDigests} 必须「按工具取最新」，而不是「取最近 N 条记录」；</li>
 *   <li>{@code selectMessageGap} 必须只把「摘要没覆盖 + 窗口没装」的那几条算成空洞。</li>
 * </ol>
 *
 * <p>为什么用 H2 真跑而不是 mock：这两条的语义全在 SQL 里（{@code GROUP BY tool_name} + {@code MAX(id)}、
 * {@code LIMIT 1 OFFSET n} 定位窗口起点），mock 掉 mapper 等于什么都没验证。
 */
class ChatDigestAndGapSqlTest {

    private static final long SESSION = 9L;

    @Test
    void digestsAreLatestPerToolInsteadOfLatestRows() throws Exception {
        var datasource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:digest_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE chat_tool_call (id BIGINT PRIMARY KEY, session_id BIGINT, tool_name VARCHAR(64), "
                    + "result_digest VARCHAR(512), gmt_create TIMESTAMP)");
            sql.execute("INSERT INTO chat_tool_call VALUES "
                    + "(1," + SESSION + ",'query_glucose_range','glucose-A',CURRENT_TIMESTAMP),"
                    + "(2," + SESSION + ",'get_health_profile','profile-B',CURRENT_TIMESTAMP),"
                    + "(3," + SESSION + ",'query_glucose_range','glucose-C',CURRENT_TIMESTAMP),"
                    + "(4," + SESSION + ",'query_glucose_range','',CURRENT_TIMESTAMP),"
                    + "(5," + SESSION + ",'query_meal_records','meal-D',CURRENT_TIMESTAMP),"
                    + "(6,8,'query_glucose_range','other-session',CURRENT_TIMESTAMP)");
        }
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatToolCallMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var mapper = session.getMapper(ChatToolCallMapper.class);

            // 三个不同工具各自的最新一条：先按 id 倒序，所以最近用过的 meal 排第一
            List<ChatToolCall> rows = mapper.selectRecentDigests(SESSION, 3);
            assertEquals(List.of("query_meal_records", "query_glucose_range", "get_health_profile"),
                    rows.stream().map(ChatToolCall::getToolName).toList());
            // 同工具只留最新那条（glucose-C），空 digest 的行被过滤，别人的会话不串进来
            assertEquals("glucose-C", rows.get(1).getResultDigest());

            // limit 语义是「最多几个工具」，不是「最多几条记录」
            assertEquals(List.of("query_meal_records"),
                    mapper.selectRecentDigests(SESSION, 1).stream().map(ChatToolCall::getToolName).toList());
        }
    }

    @Test
    void gapCountsOnlyMessagesBetweenSummaryAndWindow() throws Exception {
        var datasource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:gap_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE chat_message (id BIGINT PRIMARY KEY, session_id BIGINT, is_deleted INT)");
            sql.execute("INSERT INTO chat_message VALUES (1," + SESSION + ",0),(2," + SESSION + ",0),"
                    + "(3," + SESSION + ",0),(4," + SESSION + ",0),(5," + SESSION + ",0),"
                    + "(6," + SESSION + ",0),(7," + SESSION + ",0),(8," + SESSION + ",0),"
                    + "(9," + SESSION + ",0),(10," + SESSION + ",0)");
        }
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatSessionMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var mapper = session.getMapper(ChatSessionMapper.class);

            // 摘要覆盖到 #3、窗口装下最近 7 条（最老是 #4）→ 首尾相接，没有空洞
            MessageGap touching = mapper.selectMessageGap(SESSION, 3L, 3);
            assertEquals(0, touching.getMissing());

            // 摘要覆盖到 #3、窗口只装下最近 5 条（最老是 #6）→ #4、#5 谁也没装
            MessageGap gap = mapper.selectMessageGap(SESSION, 3L, 5);
            assertEquals(2, gap.getMissing());
            assertEquals(4L, gap.getFirstMissingId());

            // 摘要覆盖到最后一条：无论如何都没有空洞
            assertTrue(mapper.selectMessageGap(SESSION, 10L, 5).getMissing() == 0);
        }
    }
}
