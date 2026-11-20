package com.threadfit.glucopilot.dashboard.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.threadfit.glucopilot.dashboard.mapper.AdminDashboardMapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

class AdminDashboardServiceTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private AdminDashboardService service;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 10, 0);

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:dashboard_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,account_status INT,is_deleted"
                    + " INT,gmt_create TIMESTAMP,last_login_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE glucose_record(id BIGINT PRIMARY KEY,user_id BIGINT,record_date"
                    + " DATE,node_code VARCHAR(32),glucose_value DECIMAL(4,1),is_deleted INT)");
        jdbc.execute(
                "CREATE TABLE meal_record(id BIGINT PRIMARY KEY,user_id BIGINT,record_date"
                    + " DATE,node_code VARCHAR(32),predict_now DECIMAL(4,1),is_deleted INT)");
        jdbc.execute(
                "CREATE TABLE cms_news(id BIGINT PRIMARY KEY,channel_code INT,publish_status"
                    + " INT,publish_at TIMESTAMP,is_deleted INT)");
        jdbc.execute(
                "CREATE TABLE community_article_view(id BIGINT PRIMARY KEY,news_id"
                    + " BIGINT,created_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE feedback(id BIGINT PRIMARY KEY,official_reply"
                    + " VARCHAR(1000),is_deleted INT)");
        jdbc.execute(
                "CREATE TABLE food_nutrition(id BIGINT PRIMARY KEY,shelf_status INT,is_deleted"
                    + " INT)");
        jdbc.execute(
                "CREATE TABLE dict_glucose_node(id BIGINT PRIMARY KEY,node_code"
                    + " VARCHAR(32),node_name VARCHAR(32),sort_no INT,is_deleted INT)");
        jdbc.update(
                "INSERT INTO user_account VALUES"
                        + "(1,1,0,'2026-10-01 00:00:00','2026-11-19 08:00:00'),"
                        + "(2,1,0,'2026-11-19 00:00:00','2026-11-19 08:00:00'),"
                        + "(3,1,0,'2026-11-06 00:00:00','2026-11-05 08:00:00'),"
                        + "(4,1,1,'2026-11-19 00:00:00','2026-11-19 08:00:00'),"
                        + "(5,3,0,'2026-11-19 00:00:00','2026-11-19 08:00:00'),"
                        + "(6,2,0,'2026-11-19 00:00:00','2026-11-19 08:00:00'),"
                        + "(7,1,0,'2026-11-20 00:00:00','2026-11-20 08:00:00')");
        jdbc.update(
                "INSERT INTO glucose_record VALUES"
                        + "(1,1,'2026-11-06','before_breakfast',5.0,0),"
                        + "(2,1,'2026-11-19','after_breakfast',8.0,0),"
                        + "(3,2,'2026-11-19','before_breakfast',6.0,0),"
                        + "(4,1,'2026-11-05','before_breakfast',4.0,0),"
                        + "(5,1,'2026-11-20','before_breakfast',4.0,0),"
                        + "(6,1,'2026-11-19','before_breakfast',4.0,1),"
                        + "(7,4,'2026-11-19','before_breakfast',4.0,0),"
                        + "(8,5,'2026-11-19','before_breakfast',4.0,0),"
                        + "(9,6,'2026-11-19','before_dinner',3.8,0)");
        jdbc.update(
                "INSERT INTO meal_record VALUES"
                    + "(1,1,'2026-11-19','breakfast',20,0),(2,1,'2026-11-19','lunch',20,0),"
                    + "(3,2,'2026-11-06','breakfast',20,0),(4,1,'2026-11-05','breakfast',20,0),"
                    + "(5,1,'2026-11-20','breakfast',20,0),(6,1,'2026-11-19','breakfast',20,1),"
                    + "(7,4,'2026-11-19','breakfast',20,0),(8,5,'2026-11-19','breakfast',20,0)");
        jdbc.update(
                "INSERT INTO cms_news VALUES"
                        + "(1,4,1,'2026-11-19 08:00:00',0),(2,4,1,'2026-11-20 08:00:00',0),"
                        + "(3,2,0,'2026-11-18 08:00:00',0),(4,3,1,'2026-11-18 08:00:00',1),"
                        + "(5,1,1,'2026-11-01 08:00:00',0)");
        jdbc.update(
                "INSERT INTO community_article_view VALUES"
                        + "(1,1,'2026-11-19 09:00:00'),(2,1,'2026-11-19 11:00:00'),"
                        + "(3,2,'2026-11-19 09:00:00'),(4,5,'2026-11-01 09:00:00')");
        jdbc.update("INSERT INTO feedback VALUES(1,NULL,0),(2,'   ',0),(3,'已回复',0),(4,NULL,1)");
        jdbc.update("INSERT INTO food_nutrition VALUES(1,1,0),(2,0,0),(3,1,1)");
        String[] codes = {
            "before_breakfast",
            "breakfast",
            "after_breakfast",
            "before_lunch",
            "lunch",
            "after_lunch",
            "before_dinner",
            "dinner",
            "after_dinner"
        };
        String[] names = {"早餐前", "早餐", "早餐后", "中餐前", "中餐", "中餐后", "晚餐前", "晚餐", "晚餐后"};
        for (int i = 0; i < codes.length; i++)
            jdbc.update(
                    "INSERT INTO dict_glucose_node VALUES(?,?,?,?,0)",
                    i + 1,
                    codes[i],
                    names[i],
                    i + 1);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(AdminDashboardMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        service = new AdminDashboardService(session.getMapper(AdminDashboardMapper.class));
    }

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    void liveCountsExcludeDeletedCancelledFutureAndPredictedValues() {
        var result = service.summary(NOW);
        assertEquals(5, result.users().total());
        assertEquals(2, result.users().today());
        assertEquals(2, result.users().active());
        assertEquals(3, result.records().todayGlucose());
        assertEquals(2, result.records().todayMeal());
        assertEquals(5, result.records().monthTotal());
        assertEquals(2, result.content().feedbackPending());
        assertEquals(2, result.content().newsCount());
        assertEquals(2, result.content().newsViews());
        assertEquals(2, result.foodCount());
        assertEquals(2, result.channelDist().stream().mapToLong(row -> row.value()).sum());
    }

    @Test
    void fourteenDaysAreAscendingAndMissingDaysAreZeroWithoutJoinMultiplication() {
        var result = service.summary(NOW);
        assertEquals(14, result.trend().size());
        assertEquals(LocalDate.of(2026, 11, 6), result.rangeStart());
        assertEquals(LocalDate.of(2026, 11, 19), result.rangeEnd());
        assertEquals(1, result.trend().getFirst().glucose());
        assertEquals(1, result.trend().getFirst().meal());
        assertEquals(1, result.trend().getFirst().users());
        assertEquals(0, result.trend().get(1).glucose());
        assertEquals(3, result.trend().getLast().glucose());
        assertEquals(2, result.trend().getLast().meal());
        assertEquals(2, result.trend().getLast().users());
        for (int i = 0; i < 14; i++)
            assertEquals(result.rangeStart().plusDays(i), result.trend().get(i).date());
        assertEquals(9, result.nodeHeat().size());
        assertEquals("早餐前", result.nodeHeat().getFirst().node());
        assertEquals(2, result.nodeHeat().getFirst().value());
        assertEquals(7, result.nodeHeat().stream().mapToLong(row -> row.value()).sum());
        assertEquals(1, result.complianceDist().get(0).value());
        assertEquals(1, result.complianceDist().get(1).value());
        assertEquals(1, result.complianceDist().get(2).value());
    }

    @Test
    void yearBoundaryAndEmptyRecordsReturnActualZeros() {
        var result = service.summary(LocalDateTime.of(2026, 1, 3, 0, 1));
        assertEquals(LocalDate.of(2025, 12, 21), result.rangeStart());
        assertEquals(LocalDate.of(2026, 1, 3), result.rangeEnd());
        assertEquals(0, result.records().monthTotal());
        assertTrue(
                result.trend().stream()
                        .allMatch(
                                row -> row.glucose() == 0 && row.meal() == 0 && row.users() == 0));
        for (String table :
                new String[] {
                    "user_account",
                    "glucose_record",
                    "meal_record",
                    "cms_news",
                    "community_article_view",
                    "feedback",
                    "food_nutrition"
                }) jdbc.update("DELETE FROM " + table);
        session.clearCache();
        var empty = service.summary(NOW);
        assertEquals(0, empty.users().total());
        assertEquals(0, empty.foodCount());
        assertEquals(14, empty.trend().size());
        assertEquals(0, empty.complianceDist().stream().mapToLong(row -> row.value()).sum());
    }
}
