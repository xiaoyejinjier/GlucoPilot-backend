package com.threadfit.glucopilot.notification.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.threadfit.glucopilot.common.mybatis.MyBatisTestMappers;
import com.threadfit.glucopilot.notification.mapper.AdminWorkReminderMapper;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

class AdminWorkReminderServiceTest {
    private JdbcTemplate jdbc;
    private AdminWorkReminderService service;

    @BeforeEach
    void setup() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:reminders_"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE community_report(id BIGINT PRIMARY KEY,state VARCHAR(16),created_at"
                        + " TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE feedback(id BIGINT PRIMARY KEY,reply_status INT,is_deleted"
                        + " INT,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE food_nutrition(id BIGINT PRIMARY KEY,nutrition_source"
                        + " VARCHAR(32),shelf_status INT,is_deleted INT,gmt_create"
                        + " TIMESTAMP,gmt_modified TIMESTAMP)");
        jdbc.update(
                "INSERT INTO community_report VALUES(1,'OPEN','2026-10-23"
                    + " 10:00:00'),(2,'RESOLVED','2026-10-23 10:01:00'),(3,'DISMISSED','2026-10-23"
                    + " 10:02:00')");
        jdbc.update(
                "INSERT INTO feedback(id,reply_status,is_deleted,gmt_create)"
                        + " VALUES(1,0,0,'2026-10-23 11:00:00'),(2,1,0,'2026-10-23"
                        + " 12:00:00'),(3,2,0,'2026-10-23 13:00:00'),(4,3,0,'2026-10-23"
                        + " 14:00:00'),(5,0,1,'2026-10-23 15:00:00')");
        jdbc.execute("ALTER TABLE feedback ADD official_reply VARCHAR(1000)");
        jdbc.update("UPDATE feedback SET official_reply='历史回复' WHERE id IN (2,3,4)");
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT,account_status"
                        + " INT)");
        jdbc.execute(
                "CREATE TABLE glucose_record(id BIGINT PRIMARY KEY,user_id BIGINT,glucose_value"
                    + " DECIMAL(4,1),recorded_at TIMESTAMP(3),gmt_modified TIMESTAMP(3),is_deleted"
                    + " INT)");
        jdbc.execute(
                "CREATE TABLE gp_glucose_alert_case(id BIGINT PRIMARY KEY,record_id"
                        + " BIGINT,glucose_value DECIMAL(4,1),recorded_at TIMESTAMP(3),handled_at"
                        + " TIMESTAMP(3))");
        jdbc.execute(
                "CREATE TABLE admin_work_reminder_read(ops_id BIGINT,kind VARCHAR(32),source_id"
                        + " BIGINT,revision VARCHAR(255),read_at TIMESTAMP(3) DEFAULT"
                        + " CURRENT_TIMESTAMP(3),PRIMARY KEY(ops_id,kind,source_id,revision))");
        jdbc.execute(
                "ALTER TABLE glucose_record ADD node_code VARCHAR(32) DEFAULT 'before_breakfast'");
        jdbc.execute("ALTER TABLE gp_glucose_alert_case ADD status VARCHAR(16) DEFAULT 'RESOLVED'");
        jdbc.execute(
                "CREATE TABLE consultation_doctor(user_id BIGINT PRIMARY KEY,status"
                        + " VARCHAR(16),version BIGINT,updated_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE wallet_withdrawal_order(order_no VARCHAR(64) PRIMARY KEY,status"
                        + " VARCHAR(16),version BIGINT,created_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE wallet_withdrawal_event(id BIGINT PRIMARY KEY,order_no"
                        + " VARCHAR(64),event_type VARCHAR(16))");
        jdbc.execute(
                "CREATE TABLE cms_news(id BIGINT PRIMARY KEY,community_managed INT,is_deleted"
                        + " INT)");
        jdbc.execute(
                "CREATE TABLE community_article(news_id BIGINT PRIMARY KEY,state"
                        + " VARCHAR(32),revision BIGINT,updated_at TIMESTAMP)");
        service =
                new AdminWorkReminderService(
                        MyBatisTestMappers.mapper(jdbc, AdminWorkReminderMapper.class));
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void readingIsPerOperatorAndKeepsPendingWorkWhileNewSourceStaysUnread() {
        var permission = Set.of("content:news:list");
        var item = service.summary(7L, permission).items().getFirst();
        var input = new AdminWorkReminderService.ReadRequest(Map.of("REPORT", item.revision()));
        service.read(7L, permission, input);
        service.read(7L, permission, input);
        var read = service.summary(7L, permission);
        assertEquals(0, read.total());
        assertEquals(1, read.items().getFirst().pendingCount());
        assertEquals(1, service.summary(8L, permission).total());
        assertEquals(
                "OPEN",
                jdbc.queryForObject("SELECT state FROM community_report WHERE id=1", String.class));
        jdbc.update("INSERT INTO community_report VALUES(4,'OPEN',CURRENT_TIMESTAMP)");
        assertEquals(1, service.summary(7L, permission).total());
        var conflict =
                assertThrows(
                        com.threadfit.glucopilot.common.exception.BizException.class,
                        () -> service.read(7L, permission, input));
        assertEquals(
                com.threadfit.glucopilot.common.api.ErrorCode.CONFLICT, conflict.getErrorCode());
        assertEquals(1, service.summary(7L, permission).total());
    }

    @Test
    void unauthorizedReadDoesNotMarkAnotherQueueOrPartiallySaveOtherCategories() {
        var report = service.summary(7L, Set.of("content:news:list")).items().getFirst();
        var inputs = new LinkedHashMap<String, String>();
        inputs.put("REPORT", report.revision());
        inputs.put("FEEDBACK", "a".repeat(64));
        var failure =
                assertThrows(
                        com.threadfit.glucopilot.common.exception.BizException.class,
                        () ->
                                service.read(
                                        7L,
                                        Set.of("content:news:list"),
                                        new AdminWorkReminderService.ReadRequest(inputs)));
        assertEquals(
                com.threadfit.glucopilot.common.api.ErrorCode.FORBIDDEN, failure.getErrorCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admin_work_reminder_read", Integer.class));
    }

    @Test
    void changedGlucoseRemindsEvenWhenNormalAndEveryNewMeasurementBecomesUnreadAgain() {
        jdbc.update("INSERT INTO user_account VALUES(1,0,1)");
        jdbc.update(
                "INSERT INTO"
                    + " glucose_record(id,user_id,glucose_value,recorded_at,gmt_modified,is_deleted)"
                    + " VALUES(10,1,12.6,'2026-11-09 08:00:00','2026-11-09 08:00:00',0)");
        jdbc.update(
                "INSERT INTO"
                    + " gp_glucose_alert_case(id,record_id,glucose_value,recorded_at,handled_at)"
                    + " VALUES(100,10,12.6,'2026-11-09 08:00:00','2026-11-09 09:00:00')");
        var permission = Set.of("health:alert:view");
        assertEquals(0, service.summary(7L, permission).total());
        jdbc.update(
                "UPDATE glucose_record SET glucose_value=5.2,recorded_at='2026-11-09 09:10:00'"
                        + " WHERE id=10");
        var item = service.summary(7L, permission).items().getFirst();
        assertEquals("GLUCOSE_UPDATE", item.type());
        assertEquals("10", item.recordId());
        service.read(
                7L,
                permission,
                new AdminWorkReminderService.ReadRequest(Map.of(item.type(), item.revision())));
        assertEquals(0, service.summary(7L, permission).total());
        jdbc.update("UPDATE glucose_record SET recorded_at='2026-11-09 09:11:00' WHERE id=10");
        assertEquals(1, service.summary(7L, permission).total());
        jdbc.update(
                "INSERT INTO"
                    + " gp_glucose_alert_case(id,record_id,glucose_value,recorded_at,handled_at)"
                    + " VALUES(101,10,5.2,'2026-11-09 09:11:00','2026-11-09 10:00:00')");
        assertTrue(service.summary(7L, permission).items().isEmpty());
    }

    @Test
    void summaryUsesOnlyRealOpenReportsAndReceivedUndeletedFeedbackAndSortsByTime() {
        var summary = service.summary(7L, Set.of("*:*:*"));
        assertEquals(2, summary.total());
        assertEquals(2, summary.items().size());
        assertEquals("FEEDBACK", summary.items().getFirst().type());
        assertEquals(1, summary.items().getFirst().count());
        assertEquals(11, summary.items().getFirst().latestAt().getHour());
        assertEquals("REPORT", summary.items().getLast().type());
        assertEquals(1, summary.items().getLast().count());
        jdbc.update("UPDATE community_report SET state='RESOLVED' WHERE id=1");
        jdbc.update("UPDATE feedback SET reply_status=2,official_reply='已回复' WHERE id=1");
        assertEquals(0, service.summary(7L, Set.of("*:*:*")).total());
        assertTrue(service.summary(7L, Set.of("*:*:*")).items().isEmpty());
        jdbc.update("INSERT INTO community_report VALUES(4,'OPEN',CURRENT_TIMESTAMP)");
        assertEquals(1, service.summary(7L, Set.of("*:*:*")).total());
    }

    @Test
    void everyCategoryIsFilteredByServerSidePermissions() {
        var reports = service.summary(7L, Set.of("content:news:list"));
        assertEquals(1, reports.total());
        assertEquals("REPORT", reports.items().getFirst().type());
        var feedback = service.summary(7L, Set.of("feedback:manage"));
        assertEquals(1, feedback.total());
        assertEquals("FEEDBACK", feedback.items().getFirst().type());
        var unused = mock(AdminWorkReminderMapper.class);
        assertEquals(0, new AdminWorkReminderService(unused).summary(7L, Set.of()).total());
        verifyNoInteractions(unused);
    }

    @Test
    void historicalProcessingWithReplyIsCompletedButClosedWithoutReplyStillNeedsApproval() {
        jdbc.update("UPDATE feedback SET reply_status=1,official_reply='已回复' WHERE id=1");
        assertEquals(0, service.summary(7L, Set.of("feedback:manage")).total());
        jdbc.update("UPDATE feedback SET reply_status=3,official_reply=NULL WHERE id=4");
        assertEquals(1, service.summary(7L, Set.of("feedback:manage")).total());
    }

    @Test
    void onlyUnreviewedUndeletedOffShelfFoodsAppearAndHandledFoodsDisappear() {
        jdbc.update(
                "INSERT INTO food_nutrition(id,nutrition_source,shelf_status,is_deleted,gmt_create)"
                        + " VALUES(1,'AI_ESTIMATE',0,0,'2026-10-26"
                        + " 10:00:00'),(2,'AI_ESTIMATE',0,0,'2026-10-26"
                        + " 11:00:00'),(3,'AI_REVIEWED',0,0,'2026-10-26"
                        + " 12:00:00'),(4,'MANUAL',0,0,'2026-10-26"
                        + " 13:00:00'),(5,'AI_ESTIMATE',0,1,'2026-10-26"
                        + " 14:00:00'),(6,'AI_ESTIMATE',1,0,'2026-10-26 15:00:00')");
        var summary = service.summary(7L, Set.of("food:library:manage"));
        assertEquals(2, summary.total());
        assertEquals(1, summary.items().size());
        var item = summary.items().getFirst();
        assertEquals("FOOD_REVIEW", item.type());
        assertEquals("待审核食品", item.title());
        assertEquals(11, item.latestAt().getHour());
        assertTrue(
                service.summary(7L, Set.of("feedback:manage")).items().stream()
                        .noneMatch(i -> i.type().equals("FOOD_REVIEW")));
        assertEquals(4, service.summary(7L, Set.of("*:*:*")).total());
        jdbc.update(
                "UPDATE food_nutrition SET nutrition_source='AI_REVIEWED',shelf_status=1 WHERE"
                        + " id=1");
        assertEquals(1, service.summary(7L, Set.of("food:library:manage")).total());
        jdbc.update("UPDATE food_nutrition SET is_deleted=1 WHERE id=2");
        assertTrue(service.summary(7L, Set.of("food:library:manage")).items().isEmpty());
        jdbc.update(
                "INSERT INTO food_nutrition(id,nutrition_source,shelf_status,is_deleted,gmt_create)"
                        + " VALUES(7,'AI_ESTIMATE',0,0,CURRENT_TIMESTAMP)");
        assertEquals(1, service.summary(7L, Set.of("food:library:manage")).total());
    }

    @Test
    void doctorResubmissionRemindsAgainAndOnlyActiveApplicantsAreVisible() {
        jdbc.update("INSERT INTO user_account VALUES(1,0,1),(2,1,1),(3,0,2),(4,0,1)");
        jdbc.update(
                "INSERT INTO consultation_doctor"
                    + " VALUES(1,'PENDING',0,CURRENT_TIMESTAMP),(2,'PENDING',0,CURRENT_TIMESTAMP),(3,'PENDING',0,CURRENT_TIMESTAMP),(4,'APPROVED',1,CURRENT_TIMESTAMP)");
        var permission = Set.of("doctor:review:manage");
        var item = service.summary(7L, permission).items().getFirst();
        assertEquals("DOCTOR_REVIEW", item.type());
        assertEquals(1, item.count());
        service.read(
                7L,
                permission,
                new AdminWorkReminderService.ReadRequest(Map.of(item.type(), item.revision())));
        assertEquals(0, service.summary(7L, permission).total());
        assertEquals(1, service.summary(8L, permission).total());
        jdbc.update("UPDATE consultation_doctor SET version=1 WHERE user_id=1");
        assertEquals(1, service.summary(7L, permission).total());
        assertThrows(
                com.threadfit.glucopilot.common.exception.BizException.class,
                () ->
                        service.read(
                                7L,
                                permission,
                                new AdminWorkReminderService.ReadRequest(
                                        Map.of(item.type(), item.revision()))));
        jdbc.update("UPDATE consultation_doctor SET status='APPROVED' WHERE user_id=1");
        assertTrue(service.summary(7L, permission).items().isEmpty());
    }

    @Test
    void allEightQueuesCanBeReadTogetherWithoutResolvingTheirWork() {
        jdbc.update(
                "INSERT INTO food_nutrition(id,nutrition_source,shelf_status,is_deleted,gmt_create)"
                    + " VALUES(1,'AI_ESTIMATE',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO user_account VALUES(1,0,1)");
        jdbc.update("INSERT INTO consultation_doctor VALUES(1,'PENDING',0,CURRENT_TIMESTAMP)");
        jdbc.update(
                "INSERT INTO wallet_withdrawal_order VALUES('WD1','PENDING',0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO wallet_withdrawal_event VALUES(1,'WD1','FREEZE')");
        jdbc.update("INSERT INTO cms_news VALUES(1,1,0)");
        jdbc.update("INSERT INTO community_article VALUES(1,'PENDING_ADMIN',1,CURRENT_TIMESTAMP)");
        jdbc.update(
                "INSERT INTO"
                    + " glucose_record(id,user_id,glucose_value,recorded_at,gmt_modified,is_deleted)"
                    + " VALUES(10,1,12.6,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),(11,1,5.2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
        jdbc.update(
                "INSERT INTO gp_glucose_alert_case SELECT"
                    + " 1,id,12.6,recorded_at,CURRENT_TIMESTAMP,'RESOLVED' FROM glucose_record"
                    + " WHERE id=11");
        var permission = Set.of("*:*:*");
        var snapshot = service.summary(7L, permission);
        assertEquals(8, snapshot.items().size());
        assertEquals(8, snapshot.total());
        var revisions = new HashMap<String, String>();
        snapshot.items().forEach(item -> revisions.put(item.type(), item.revision()));
        service.read(7L, permission, new AdminWorkReminderService.ReadRequest(revisions));
        var read = service.summary(7L, permission);
        assertEquals(0, read.total());
        assertEquals(
                8,
                read.items().stream().mapToLong(AdminWorkReminderService.Item::pendingCount).sum());
        assertEquals(8, service.summary(8L, permission).total());
    }

    @Test
    void withdrawalRemindersFollowPendingOrdersAndDoNotChangeMoneyOrApproval() {
        jdbc.update(
                "INSERT INTO wallet_withdrawal_order"
                    + " VALUES('WD1','PENDING',0,CURRENT_TIMESTAMP),('WD2','APPROVED',1,CURRENT_TIMESTAMP)");
        jdbc.update(
                "INSERT INTO wallet_withdrawal_event"
                        + " VALUES(1,'WD1','FREEZE'),(2,'WD2','FREEZE'),(3,'WD2','PAYOUT')");
        var permission = Set.of("doctor:withdrawal:manage");
        var item = service.summary(7L, permission).items().getFirst();
        assertEquals("WITHDRAWAL_REVIEW", item.type());
        assertEquals(1, item.count());
        service.read(
                7L,
                permission,
                new AdminWorkReminderService.ReadRequest(Map.of(item.type(), item.revision())));
        assertEquals(0, service.summary(7L, permission).total());
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT status FROM wallet_withdrawal_order WHERE order_no='WD1'",
                        String.class));
        jdbc.update("UPDATE wallet_withdrawal_order SET status='REJECTED' WHERE order_no='WD1'");
        assertTrue(service.summary(7L, permission).items().isEmpty());
    }

    @Test
    void articleFinalReviewAndGlucosePendingUseRealWorkflowStatesAndPermissions() {
        jdbc.update("INSERT INTO cms_news VALUES(1,1,0),(2,1,1),(3,1,0),(4,0,0)");
        jdbc.update(
                "INSERT INTO community_article"
                    + " VALUES(1,'PENDING_ADMIN',1,CURRENT_TIMESTAMP),(2,'PENDING_ADMIN',1,CURRENT_TIMESTAMP),(3,'AI_PENDING',1,CURRENT_TIMESTAMP),(4,'PENDING_ADMIN',1,CURRENT_TIMESTAMP)");
        assertEquals(
                1,
                service.summary(7L, Set.of("content:news:list")).items().stream()
                        .filter(i -> i.type().equals("ARTICLE_REVIEW"))
                        .findFirst()
                        .orElseThrow()
                        .count());
        assertTrue(service.summary(7L, Set.of("doctor:review:manage")).items().isEmpty());
        jdbc.update("INSERT INTO user_account VALUES(1,0,1)");
        jdbc.update(
                "INSERT INTO"
                    + " glucose_record(id,user_id,glucose_value,recorded_at,gmt_modified,is_deleted)"
                    + " VALUES(10,1,12.6,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),(11,1,5.2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),(12,1,12.6,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
        var permission = Set.of("health:alert:view");
        var item = service.summary(7L, permission).items().getFirst();
        assertEquals("GLUCOSE_PENDING", item.type());
        assertEquals("10", item.recordId());
        assertEquals(1, item.count());
        service.read(
                7L,
                permission,
                new AdminWorkReminderService.ReadRequest(Map.of(item.type(), item.revision())));
        assertEquals(0, service.summary(7L, permission).total());
        jdbc.update(
                "INSERT INTO gp_glucose_alert_case SELECT"
                        + " 1,id,glucose_value,recorded_at,CURRENT_TIMESTAMP,'PROCESSING' FROM"
                        + " glucose_record WHERE id=10");
        assertTrue(service.summary(7L, permission).items().isEmpty());
    }
}
