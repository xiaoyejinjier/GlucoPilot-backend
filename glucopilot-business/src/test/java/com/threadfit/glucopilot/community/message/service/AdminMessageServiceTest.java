package com.threadfit.glucopilot.community.message.service;

import static org.junit.jupiter.api.Assertions.*;

import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.message.mapper.AdminMessageMapper;
import com.threadfit.glucopilot.community.shared.support.CommunityTestMappers;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

class AdminMessageServiceTest {
    private JdbcTemplate jdbc;
    private AdminMessageService service;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:admin_messages_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE user_profile(user_id BIGINT PRIMARY KEY,nickname"
                        + " VARCHAR(64),is_deleted INT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE community_message(id BIGINT PRIMARY KEY,sender_id BIGINT,recipient_id"
                        + " BIGINT,content VARCHAR(1000),client_request_id VARCHAR(64),created_at"
                        + " TIMESTAMP,read_at TIMESTAMP)");
        jdbc.update(
                "INSERT INTO user_profile(user_id,nickname,is_deleted)"
                        + " VALUES(1,'甲',0),(2,'乙',0),(3,'已删除资料',1)");
        for (int id = 1; id <= 5; id++) {
            long sender = id == 2 ? 2 : 1;
            long recipient = id == 2 ? 1 : id == 5 ? 3 : 2;
            jdbc.update(
                    "INSERT INTO community_message VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,?)",
                    id,
                    sender,
                    recipient,
                    id == 3 ? "含%_字符" : "消息" + id,
                    "request-" + id,
                    id == 2 ? java.sql.Timestamp.valueOf("2026-10-28 10:00:00") : null);
        }
        service =
                new AdminMessageService(
                        CommunityTestMappers.mapper(jdbc, AdminMessageMapper.class));
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    private PageQuery page(int number, int size) {
        var q = new PageQuery();
        q.setPageNum(number);
        q.setPageSize(size);
        return q;
    }

    @Test
    void mapsRealMessagesWithStableOrderingPagingAndDeletedProfileFallback() {
        var result = service.messages(null, null, null, null, page(1, 2));
        assertEquals(5, result.getTotal());
        assertEquals(3, result.getPages());
        assertEquals(List.of("5", "4"), result.getRecords().stream().map(m -> m.id()).toList());
        var message = result.getRecords().getFirst();
        assertEquals("甲", message.senderNickname());
        assertEquals("用户 3", message.recipientNickname());
        assertEquals("1", message.senderId());
        assertNotNull(message.createdAt());
        assertNull(message.readAt());
        assertEquals(
                List.of("3", "2"),
                service.messages(null, null, null, null, page(2, 2)).getRecords().stream()
                        .map(m -> m.id())
                        .toList());
    }

    @Test
    void filtersContentNicknameDirectionAndReadStateWithoutWildcardExpansion() {
        assertEquals(1, service.messages(" %_ ", null, null, null, page(1, 10)).getTotal());
        assertEquals(0, service.messages("' OR 1=1 --", null, null, null, page(1, 10)).getTotal());
        assertEquals(4, service.messages("乙", null, null, null, page(1, 10)).getTotal());
        assertEquals(3, service.messages(null, 1L, 2L, "UNREAD", page(1, 10)).getTotal());
        var read = service.messages(null, null, null, "READ", page(1, 10));
        assertEquals("2", read.getRecords().getFirst().id());
        assertNotNull(read.getRecords().getFirst().readAt());
    }

    @Test
    void conversationIncludesBothDirectionsAndNeverMarksMessagesRead() {
        var result = service.conversation(1, 2, page(1, 100));
        assertEquals(
                List.of("4", "3", "2", "1"),
                result.getRecords().stream().map(m -> m.id()).toList());
        assertEquals(result, service.conversation(2, 1, page(1, 100)));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM community_message WHERE read_at IS NOT NULL",
                        Integer.class));
        assertEquals(
                5, jdbc.queryForObject("SELECT COUNT(*) FROM community_message", Integer.class));
    }

    @Test
    void validatesQueriesAndReturnsEmptyPages() {
        assertThrows(
                BizException.class,
                () -> service.messages("x".repeat(81), null, null, null, page(1, 10)));
        assertThrows(BizException.class, () -> service.messages(null, 0L, null, null, page(1, 10)));
        assertThrows(
                BizException.class, () -> service.messages(null, null, -1L, null, page(1, 10)));
        assertThrows(
                BizException.class, () -> service.messages(null, null, null, "wrong", page(1, 10)));
        assertThrows(BizException.class, () -> service.conversation(1, 1, page(1, 10)));
        assertThrows(BizException.class, () -> service.conversation(0, 1, page(1, 10)));
        assertThrows(BizException.class, () -> service.conversation(1, 2, page(1, 101)));
        assertThrows(BizException.class, () -> service.conversation(1, 2, page(0, 10)));
        var empty = service.conversation(88, 99, page(1, 10));
        assertEquals(0, empty.getTotal());
        assertTrue(empty.getRecords().isEmpty());
    }
}
