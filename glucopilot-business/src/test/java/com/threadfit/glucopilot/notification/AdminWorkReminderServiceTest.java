package com.threadfit.glucopilot.notification;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminWorkReminderServiceTest {
    private JdbcTemplate jdbc;
    private AdminWorkReminderService service;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:reminders_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE community_report(id BIGINT PRIMARY KEY,state VARCHAR(16),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE feedback(id BIGINT PRIMARY KEY,reply_status INT,is_deleted INT,gmt_create TIMESTAMP)");
        jdbc.execute("CREATE TABLE food_nutrition(id BIGINT PRIMARY KEY,nutrition_source VARCHAR(32),shelf_status INT,is_deleted INT,gmt_create TIMESTAMP)");
        jdbc.update("INSERT INTO community_report VALUES(1,'OPEN','2026-10-23 10:00:00'),(2,'RESOLVED','2026-10-23 10:01:00'),(3,'DISMISSED','2026-10-23 10:02:00')");
        jdbc.update("INSERT INTO feedback VALUES(1,0,0,'2026-10-23 11:00:00'),(2,1,0,'2026-10-23 12:00:00'),(3,2,0,'2026-10-23 13:00:00'),(4,3,0,'2026-10-23 14:00:00'),(5,0,1,'2026-10-23 15:00:00')");
        jdbc.execute("ALTER TABLE feedback ADD official_reply VARCHAR(1000)");
        jdbc.update("UPDATE feedback SET official_reply='历史回复' WHERE id IN (2,3,4)");
        service=new AdminWorkReminderService(jdbc);
    }
    @AfterEach void cleanup(){jdbc.execute("DROP ALL OBJECTS");}
    @Test void summaryUsesOnlyRealOpenReportsAndReceivedUndeletedFeedbackAndSortsByTime() {
        var summary=service.summary(Set.of("*:*:*"));assertEquals(2,summary.total());assertEquals(2,summary.items().size());
        assertEquals("FEEDBACK",summary.items().getFirst().type());assertEquals(1,summary.items().getFirst().count());assertEquals(11,summary.items().getFirst().latestAt().getHour());
        assertEquals("REPORT",summary.items().getLast().type());assertEquals(1,summary.items().getLast().count());
        jdbc.update("UPDATE community_report SET state='RESOLVED' WHERE id=1");jdbc.update("UPDATE feedback SET reply_status=2,official_reply='已回复' WHERE id=1");
        assertEquals(0,service.summary(Set.of("*:*:*")).total());assertTrue(service.summary(Set.of("*:*:*")).items().isEmpty());
        jdbc.update("INSERT INTO community_report VALUES(4,'OPEN',CURRENT_TIMESTAMP)");assertEquals(1,service.summary(Set.of("*:*:*")).total());
    }
    @Test void everyCategoryIsFilteredByServerSidePermissions() {
        var reports=service.summary(Set.of("content:news:list"));assertEquals(1,reports.total());assertEquals("REPORT",reports.items().getFirst().type());
        var feedback=service.summary(Set.of("feedback:manage"));assertEquals(1,feedback.total());assertEquals("FEEDBACK",feedback.items().getFirst().type());
        var unused=mock(JdbcTemplate.class);assertEquals(0,new AdminWorkReminderService(unused).summary(Set.of()).total());verifyNoInteractions(unused);
    }
    @Test void historicalProcessingWithReplyIsCompletedButClosedWithoutReplyStillNeedsApproval() {
        jdbc.update("UPDATE feedback SET reply_status=1,official_reply='已回复' WHERE id=1");
        assertEquals(0,service.summary(Set.of("feedback:manage")).total());
        jdbc.update("UPDATE feedback SET reply_status=3,official_reply=NULL WHERE id=4");
        assertEquals(1,service.summary(Set.of("feedback:manage")).total());
    }

    @Test void onlyUnreviewedUndeletedOffShelfFoodsAppearAndHandledFoodsDisappear() {
        jdbc.update("INSERT INTO food_nutrition VALUES(1,'AI_ESTIMATE',0,0,'2026-10-26 10:00:00'),(2,'AI_ESTIMATE',0,0,'2026-10-26 11:00:00'),(3,'AI_REVIEWED',0,0,'2026-10-26 12:00:00'),(4,'MANUAL',0,0,'2026-10-26 13:00:00'),(5,'AI_ESTIMATE',0,1,'2026-10-26 14:00:00'),(6,'AI_ESTIMATE',1,0,'2026-10-26 15:00:00')");
        var summary = service.summary(Set.of("food:library:manage"));
        assertEquals(2, summary.total()); assertEquals(1, summary.items().size());
        var item = summary.items().getFirst(); assertEquals("FOOD_REVIEW", item.type());
        assertEquals("待审核食品", item.title()); assertEquals(11, item.latestAt().getHour());
        assertTrue(service.summary(Set.of("feedback:manage")).items().stream().noneMatch(i -> i.type().equals("FOOD_REVIEW")));
        assertEquals(4, service.summary(Set.of("*:*:*")).total());
        jdbc.update("UPDATE food_nutrition SET nutrition_source='AI_REVIEWED',shelf_status=1 WHERE id=1");
        assertEquals(1, service.summary(Set.of("food:library:manage")).total());
        jdbc.update("UPDATE food_nutrition SET is_deleted=1 WHERE id=2");
        assertTrue(service.summary(Set.of("food:library:manage")).items().isEmpty());
        jdbc.update("INSERT INTO food_nutrition VALUES(7,'AI_ESTIMATE',0,0,CURRENT_TIMESTAMP)");
        assertEquals(1, service.summary(Set.of("food:library:manage")).total());
    }
}
