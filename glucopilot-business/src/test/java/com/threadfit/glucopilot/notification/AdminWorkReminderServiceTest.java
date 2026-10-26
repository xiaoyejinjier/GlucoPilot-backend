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
        jdbc.update("INSERT INTO community_report VALUES(1,'OPEN','2026-10-23 10:00:00'),(2,'RESOLVED','2026-10-23 10:01:00'),(3,'DISMISSED','2026-10-23 10:02:00')");
        jdbc.update("INSERT INTO feedback VALUES(1,0,0,'2026-10-23 11:00:00'),(2,1,0,'2026-10-23 12:00:00'),(3,2,0,'2026-10-23 13:00:00'),(4,3,0,'2026-10-23 14:00:00'),(5,0,1,'2026-10-23 15:00:00')");
        service=new AdminWorkReminderService(jdbc);
    }
    @AfterEach void cleanup(){jdbc.execute("DROP ALL OBJECTS");}
    @Test void summaryUsesOnlyRealOpenReportsAndReceivedUndeletedFeedbackAndSortsByTime() {
        var summary=service.summary(Set.of("*:*:*"));assertEquals(2,summary.total());assertEquals(2,summary.items().size());
        assertEquals("FEEDBACK",summary.items().getFirst().type());assertEquals(1,summary.items().getFirst().count());assertEquals(11,summary.items().getFirst().latestAt().getHour());
        assertEquals("REPORT",summary.items().getLast().type());assertEquals(1,summary.items().getLast().count());
        jdbc.update("UPDATE community_report SET state='RESOLVED' WHERE id=1");jdbc.update("UPDATE feedback SET reply_status=1 WHERE id=1");
        assertEquals(0,service.summary(Set.of("*:*:*")).total());assertTrue(service.summary(Set.of("*:*:*")).items().isEmpty());
        jdbc.update("INSERT INTO community_report VALUES(4,'OPEN',CURRENT_TIMESTAMP)");assertEquals(1,service.summary(Set.of("*:*:*")).total());
    }
    @Test void everyCategoryIsFilteredByServerSidePermissions() {
        var reports=service.summary(Set.of("content:news:list"));assertEquals(1,reports.total());assertEquals("REPORT",reports.items().getFirst().type());
        var feedback=service.summary(Set.of("feedback:manage"));assertEquals(1,feedback.total());assertEquals("FEEDBACK",feedback.items().getFirst().type());
        var unused=mock(JdbcTemplate.class);assertEquals(0,new AdminWorkReminderService(unused).summary(Set.of()).total());verifyNoInteractions(unused);
    }
}
