package com.threadfit.glucopilot.notification.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.notification.dto.UserReminderDtos;
import com.threadfit.glucopilot.notification.mapper.UserReminderMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

public class UserReminderServiceTest {
    private JdbcTemplate jdbc;
    private UserReminderService service;
    private UserReminderMapper mapper;
    private TransactionTemplate tx;
    private java.sql.Connection migrationConnection;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    public static String sha2(String text, int bits) {
        return text == null ? null : UserReminderService.hash(text);
    }

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:reminders_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS IF NOT EXISTS SHA2 FOR"
                    + " 'com.threadfit.glucopilot.notification.service.UserReminderServiceTest.sha2'");
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT DEFAULT"
                        + " 0,account_status INT DEFAULT 1)");
        jdbc.execute(
                "CREATE TABLE glucose_record(id BIGINT PRIMARY KEY,user_id BIGINT,record_date"
                        + " DATE,node_code VARCHAR(32),glucose_value DECIMAL(3,1),recorded_at"
                        + " TIMESTAMP(3),is_deleted INT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE gp_glucose_alert_case(id BIGINT PRIMARY KEY,record_id"
                        + " BIGINT,glucose_value DECIMAL(3,1),recorded_at TIMESTAMP(3),user_reply"
                        + " VARCHAR(1000),replied_at TIMESTAMP(3),note VARCHAR(1000))");
        jdbc.execute(
                "CREATE TABLE community_message(id BIGINT PRIMARY KEY,sender_id BIGINT,recipient_id"
                        + " BIGINT,read_at TIMESTAMP(3))");
        jdbc.execute(
                "CREATE TABLE community_notification(id BIGINT PRIMARY KEY,recipient_id BIGINT,kind"
                        + " VARCHAR(16),read_at TIMESTAMP(3))");
        jdbc.execute(
                "CREATE TABLE community_report(id BIGINT PRIMARY KEY,reporter_id BIGINT,state"
                        + " VARCHAR(32),resolved_at TIMESTAMP(3),feedback_read_at TIMESTAMP(3))");
        var migration =
                new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V53__user_reminder_reads.sql"));
        migrationConnection = ds.getConnection();
        migration.populate(migrationConnection);
        jdbc.update("INSERT INTO user_account(id) VALUES(1),(2)");
        jdbc.update(
                "INSERT INTO glucose_record"
                        + " VALUES(10,1,'2026-11-05','before_breakfast',12.6,'2026-11-05"
                        + " 08:00:00',0),(11,1,'2026-11-05','after_breakfast',5.2,'2026-11-05"
                        + " 10:00:00',0),(20,2,'2026-11-05','before_breakfast',3.2,'2026-11-05"
                        + " 08:00:00',0)");
        jdbc.update(
                "INSERT INTO gp_glucose_alert_case VALUES(100,10,12.6,'2026-11-05"
                        + " 08:00:00','第一次公开回复','2026-11-05 09:00:00','私有内部备注')");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UserReminderMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(UserReminderMapper.class);
        service = new UserReminderService(mapper);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            jdbc.execute("DROP ALL OBJECTS");
        } finally {
            if (migrationConnection != null) migrationConnection.close();
        }
    }

    private PageQuery page() {
        var page = new PageQuery();
        page.setPageSize(3);
        return page;
    }

    @Test
    void readAllIncludesRepliesBeyondFirstPageAndPreservesOtherUsersAndDates() {
        for (int id = 200; id < 260; id++) {
            jdbc.update(
                    "INSERT INTO gp_glucose_alert_case VALUES(?,10,12.6,'2026-11-05"
                            + " 08:00:00','更多公开回复','2026-11-05 09:00:00','内部备注')",
                    id);
        }
        jdbc.update(
                "INSERT INTO glucose_record"
                    + " VALUES(12,1,'2026-11-04','before_breakfast',12.0,'2026-11-04 08:00:00',0)");
        jdbc.update("INSERT INTO community_message VALUES(1,2,1,NULL),(2,1,2,NULL)");
        jdbc.update(
                "INSERT INTO community_notification"
                        + " VALUES(1,1,'LIKE',NULL),(2,1,'COMMENT',NULL),(3,2,'LIKE',NULL)");
        jdbc.update(
                "INSERT INTO community_report"
                    + " VALUES(1,1,'RESOLVED',CURRENT_TIMESTAMP,NULL),(2,2,'RESOLVED',CURRENT_TIMESTAMP,NULL)");
        assertEquals(61, service.list(1, DAY, page()).replies().getTotal());
        tx.executeWithoutResult(s -> service.readAll(1, DAY));
        assertTrue(service.list(1, DAY, page()).alerts().isEmpty());
        assertEquals(0, service.list(1, DAY, page()).replies().getTotal());
        assertEquals(1, service.list(1, DAY.minusDays(1), page()).alerts().size());
        assertEquals(1, service.list(2, DAY, page()).alerts().size());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_message WHERE read_at IS NULL",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_notification WHERE read_at IS NULL",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_report WHERE feedback_read_at IS NULL",
                        Integer.class));
        jdbc.update("INSERT INTO community_message VALUES(99,2,1,NULL)");
        assertNull(
                jdbc.queryForObject(
                        "SELECT read_at FROM community_message WHERE id=99", Object.class));
    }

    @Test
    void failedReadAllRollsBackEarlierReceiptAndMessageChanges() {
        jdbc.update("INSERT INTO community_message VALUES(1,2,1,NULL)");
        jdbc.execute("DROP TABLE community_notification");
        assertThrows(
                RuntimeException.class,
                () -> tx.executeWithoutResult(s -> service.readAll(1, DAY)));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM user_reminder_read", Integer.class));
        assertNull(
                jdbc.queryForObject(
                        "SELECT read_at FROM community_message WHERE id=1", Object.class));
    }

    @Test
    void readingExactGlucoseSnapshotSurvivesReloadAndNewMeasurementIsUnreadAgain() {
        var original = service.list(1, DAY, page()).alerts().getFirst();
        assertEquals("10", original.id());
        tx.executeWithoutResult(
                s ->
                        service.read(
                                1, "GLUCOSE", 10, new UserReminderDtos.Read(original.revision())));
        tx.executeWithoutResult(
                s ->
                        service.read(
                                1, "GLUCOSE", 10, new UserReminderDtos.Read(original.revision())));
        assertTrue(service.list(1, DAY, page()).alerts().isEmpty());
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM user_reminder_read", Integer.class));
        assertTrue(service.detail(1, "GLUCOSE", 10).read());
        jdbc.update(
                "UPDATE glucose_record SET glucose_value=12.5,recorded_at='2026-11-05 08:01:00'"
                        + " WHERE id=10");
        assertEquals(1, service.list(1, DAY, page()).alerts().size());
        var error =
                assertThrows(
                        BizException.class,
                        () ->
                                service.read(
                                        1,
                                        "GLUCOSE",
                                        10,
                                        new UserReminderDtos.Read(original.revision())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(1, service.list(2, DAY, page()).alerts().size());
    }

    @Test
    void replyReadDoesNotHideChangedTextEvenAtSameTimestampAndKeepsOldMeasurement() {
        var reply = service.list(1, DAY, page()).replies().getRecords().getFirst();
        tx.executeWithoutResult(
                s -> service.read(1, "REPLY", 100, new UserReminderDtos.Read(reply.revision())));
        assertEquals(0, service.list(1, DAY, page()).replies().getTotal());
        jdbc.update("UPDATE gp_glucose_alert_case SET user_reply='更新后的公开回复' WHERE id=100");
        jdbc.update("UPDATE glucose_record SET glucose_value=12.5 WHERE id=10");
        var current = service.list(1, DAY, page()).replies().getRecords().getFirst();
        assertEquals("12.6", current.glucoseValue().toPlainString());
        assertEquals("更新后的公开回复", current.userReply());
        assertFalse(current.read());
        assertThrows(
                BizException.class,
                () -> service.read(1, "REPLY", 100, new UserReminderDtos.Read(reply.revision())));
    }

    @Test
    void anotherUserCannotReadOrAcknowledgeAndDeletedOrWithdrawnContentDisappears() {
        var reply = service.detail(1, "REPLY", 100);
        assertThrows(BizException.class, () -> service.detail(2, "REPLY", 100));
        assertThrows(
                BizException.class,
                () -> service.read(2, "REPLY", 100, new UserReminderDtos.Read(reply.revision())));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM user_reminder_read", Integer.class));
        jdbc.update("UPDATE gp_glucose_alert_case SET user_reply='' WHERE id=100");
        assertEquals(0, service.list(1, DAY, page()).replies().getTotal());
        assertThrows(BizException.class, () -> service.detail(1, "REPLY", 100));
        jdbc.update("UPDATE glucose_record SET is_deleted=1 WHERE id=10");
        assertTrue(service.list(1, DAY, page()).alerts().isEmpty());
    }

    @Test
    void manualCategoryReadIsOwnerAndKindScopedAndMessageBoundaryPreservesLaterArrivals() {
        jdbc.update("INSERT INTO community_message VALUES(1,2,1,NULL),(2,1,2,NULL)");
        Long through = mapper.messageCursor(1);
        jdbc.update("INSERT INTO community_message VALUES(3,2,1,NULL)");
        mapper.readMessages(1, through);
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_message WHERE read_at IS NULL",
                        Integer.class));
        tx.executeWithoutResult(s -> service.readInformation(1, "MESSAGE"));
        assertNull(
                jdbc.queryForObject(
                        "SELECT read_at FROM community_message WHERE id=2", Object.class));
        jdbc.update(
                "INSERT INTO community_notification"
                        + " VALUES(1,1,'LIKE',NULL),(2,1,'COMMENT',NULL),(3,2,'LIKE',NULL)");
        tx.executeWithoutResult(s -> service.readInformation(1, "LIKE"));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_notification WHERE read_at IS NULL",
                        Integer.class));
        jdbc.update(
                "INSERT INTO community_report"
                    + " VALUES(1,1,'OPEN',NULL,NULL),(2,1,'RESOLVED',CURRENT_TIMESTAMP,NULL),(3,2,'RESOLVED',CURRENT_TIMESTAMP,NULL),(4,1,'OPEN',NULL,NULL)");
        var reports = mapper.reportSnapshots(1);
        jdbc.update(
                "UPDATE community_report SET state='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE"
                        + " id=1");
        mapper.readReports(1, reports);
        assertNull(
                jdbc.queryForObject(
                        "SELECT feedback_read_at FROM community_report WHERE id=1", Object.class));
        tx.executeWithoutResult(s -> service.readInformation(1, "REPORT"));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_report WHERE feedback_read_at IS NULL",
                        Integer.class));
        assertThrows(BizException.class, () -> service.readInformation(1, "OTHER"));
    }
}
