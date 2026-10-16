package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.support.OwnedMediaResolver;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHistoryTest {
    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final OwnedMediaResolver media = mock(OwnedMediaResolver.class);
    private final ChatMessageService service = new ChatMessageService(mapper, mock(ChatSessionMapper.class), sessions, media);

    @Test
    void latestPageIsChronologicalWithExclusiveOlderCursor() {
        when(mapper.selectHistory(1L, 9L, null, 3)).thenReturn(List.of(row(5), row(4), row(3)));
        ChatDtos.HistoryResponse page = service.history(1L, 9L, null, 2);
        assertEquals(List.of(4, 5), page.getRecords().stream().map(ChatDtos.HistoryMessageResponse::getSeqNo).toList());
        assertTrue(page.isHasMore());
        assertEquals(4, page.getNextBeforeSeqNo());
        when(mapper.selectHistory(1L, 9L, 4, 3)).thenReturn(List.of(row(3), row(1)));
        ChatDtos.HistoryResponse older = service.history(1L, 9L, 4, 2);
        assertEquals(List.of(1, 3), older.getRecords().stream().map(ChatDtos.HistoryMessageResponse::getSeqNo).toList());
        assertFalse(older.isHasMore());
        assertNull(older.getNextBeforeSeqNo());
    }

    @Test
    void emptyHistoryAndInvalidPagination() {
        when(mapper.selectHistory(1L, 9L, null, 51)).thenReturn(List.of());
        assertTrue(service.history(1L, 9L, null, 50).getRecords().isEmpty());
        for (int size : new int[]{0, -1, 101}) {
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                    () -> service.history(1L, 9L, null, size)).getErrorCode());
        }
        assertThrows(BizException.class, () -> service.history(1L, 9L, 0, 50));
        verify(mapper, times(1)).selectHistory(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void unownedOrMissingSessionIsRejectedBeforeReadingMessages() {
        when(sessions.requireOwned(1L, 9L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.history(1L, 9L, null, 50)).getErrorCode());
        verifyNoInteractions(mapper, media);
    }

    @Test
    void historyImagesAreResolvedWithOwnershipAndMissingMediaDoesNotEraseText() {
        ChatMessage image = row(1);
        image.setMsgType(2);
        image.setMediaId(99L);
        when(mapper.selectHistory(1L, 9L, null, 51)).thenReturn(List.of(image));
        MediaFile file = new MediaFile();
        file.setFileUrl("https://example.invalid/owned-image.jpg");
        when(media.require(99L, 1L, MediaBizType.CHAT_IMAGE)).thenReturn(file);
        assertEquals(file.getFileUrl(), service.history(1L, 9L, null, 50).getRecords().getFirst().getFileUrl());
        when(media.require(99L, 1L, MediaBizType.CHAT_IMAGE)).thenThrow(new BizException(ErrorCode.BIZ_REJECTED));
        var unavailable = service.history(1L, 9L, null, 50).getRecords().getFirst();
        assertNull(unavailable.getFileUrl());
        assertEquals("message 1", unavailable.getContentText());
    }

    @Test
    void realMapperFiltersOwnerSessionDeletedAndSystemRowsAndKeepsPureImages() throws Exception {
        var datasource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:history_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE chat_message (id BIGINT PRIMARY KEY, session_id BIGINT, user_id BIGINT, "
                    + "seq_no INT, msg_role INT, msg_type INT, content_text VARCHAR(100), is_deleted INT)");
            sql.execute("INSERT INTO chat_message VALUES "
                    + "(1,9,1,1,1,1,'old',0),(2,9,1,2,2,1,'reply',0),(3,9,1,3,1,2,NULL,0),"
                    + "(4,9,1,4,3,1,'system',0),(5,9,1,5,2,1,'deleted',1),"
                    + "(6,9,2,6,1,1,'other user',0),(7,8,1,7,1,1,'other session',0)");
        }
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatMessageMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var realMapper = session.getMapper(ChatMessageMapper.class);
            assertEquals(List.of(3, 2), realMapper.selectHistory(1L, 9L, null, 2).stream().map(ChatMessage::getSeqNo).toList());
            assertEquals(List.of(2, 1), realMapper.selectHistory(1L, 9L, 3, 50).stream().map(ChatMessage::getSeqNo).toList());
            assertTrue(realMapper.selectHistory(3L, 9L, null, 50).isEmpty());
        }
    }

    private static ChatMessage row(int seq) {
        ChatMessage row = new ChatMessage();
        row.setId((long) seq);
        row.setSeqNo(seq);
        row.setMsgRole(seq % 2 == 0 ? 2 : 1);
        row.setMsgType(1);
        row.setContentText("message " + seq);
        return row;
    }
}
