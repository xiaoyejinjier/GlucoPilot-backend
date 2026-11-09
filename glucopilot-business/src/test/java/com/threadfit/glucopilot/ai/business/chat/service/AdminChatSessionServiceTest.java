package com.threadfit.glucopilot.ai.business.chat.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.business.chat.dto.AdminChatSessionDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.AdminSessionHit;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.SessionRoundCount;
import com.threadfit.glucopilot.ai.business.chat.service.impl.AdminChatSessionServiceImpl;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.audit.OpsAuditRecorder;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端会话审计的读取规则。
 *
 * <p>只验证「投影规则」与「越权边界」：分片轮次要合进每一行、预览要截断、
 * 读全文要写审计、消息不属于该会话要 404。SQL 本身的形状靠 E2E 在真实库上验。
 */
class AdminChatSessionServiceTest {

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private ChatContextMapper contextMapper;
    private OpsAuditRecorder auditRecorder;
    private AdminChatSessionService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        contextMapper = mock(ChatContextMapper.class);
        auditRecorder = mock(OpsAuditRecorder.class);
        service = new AdminChatSessionServiceImpl(sessionMapper, messageMapper, contextMapper, auditRecorder);
    }

    @Test
    @DisplayName("列表应把分片轮次合并进每一行，没有分片的会话记 0")
    void listMergesContextRounds() {
        AdminSessionHit withRounds = hit(139L, "血糖咨询", 12);
        AdminSessionHit withoutRounds = hit(140L, "饮食记录", 0);
        when(sessionMapper.selectAdminPage(any(), eq(null), eq(null), eq(null)))
                .thenReturn(page(List.of(withRounds, withoutRounds), 2));
        when(contextMapper.selectRoundCounts(List.of(139L, 140L)))
                .thenReturn(List.of(roundCount(139L, 3)));

        PageResult<AdminChatSessionDtos.SessionRow> result = service.list(null, null, null, new PageQuery());

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).contextRounds()).isEqualTo(3);
        assertThat(result.getRecords().get(1).contextRounds()).isZero();
        assertThat(result.getRecords().get(0).hasSummary()).isTrue();
        assertThat(result.getRecords().get(0).userNickname()).isEqualTo("小明");
    }

    @Test
    @DisplayName("列表为空时不应再去查分片轮次")
    void listEmptyPageSkipsRoundQuery() {
        when(sessionMapper.selectAdminPage(any(), any(), any(), any())).thenReturn(page(List.of(), 0));

        PageResult<AdminChatSessionDtos.SessionRow> result = service.list(null, null, null, new PageQuery());

        assertThat(result.getRecords()).isEmpty();
        verifyNoInteractions(contextMapper);
    }

    @Test
    @DisplayName("关键词过长应直接 400，而不是悄悄截断搜索")
    void listRejectsOverlongKeyword() {
        String keyword = "糖".repeat(51);

        assertThatThrownBy(() -> service.list(null, null, keyword, new PageQuery()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
        verifyNoInteractions(sessionMapper);
    }

    @Test
    @DisplayName("消息预览应折叠空白并截断到 200 字，图片消息给占位文案")
    void messagesGiveTruncatedPreview() {
        when(sessionMapper.selectById(139L)).thenReturn(session(139L));
        ChatMessage longText = message(1L, 139L, 2, 1, "血".repeat(260), null, null);
        ChatMessage image = message(2L, 139L, 1, 2, null, null, null);
        when(messageMapper.selectAdminPage(any(), eq(139L), eq(null)))
                .thenReturn(page(List.of(longText, image), 2));

        PageResult<AdminChatSessionDtos.MessageRow> result = service.messages(139L, null, new PageQuery());

        String preview = result.getRecords().get(0).preview();
        // 200 是接口契约（DTO 注释里写明），这里按契约断言而不是引用实现里的包级常量
        assertThat(preview).hasSize(200 + 1);
        assertThat(preview).endsWith("…");
        assertThat(result.getRecords().get(1).preview()).isEqualTo("[图片]");
    }

    @Test
    @DisplayName("会话不存在时消息列表应 404")
    void messagesRejectUnknownSession() {
        when(sessionMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.messages(404L, null, new PageQuery()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verifyNoInteractions(messageMapper);
    }

    @Test
    @DisplayName("消息不属于该会话时应 404 且不写审计")
    void messageDetailRejectsCrossSessionMessage() {
        when(sessionMapper.selectById(139L)).thenReturn(session(139L));
        ChatMessage other = message(7L, 140L, 1, 1, "别的会话", null, null);
        when(messageMapper.selectById(7L)).thenReturn(other);

        assertThatThrownBy(() -> service.messageDetail(139L, 7L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    @DisplayName("读全文应返回完整字段并写一条只含结构信息的审计")
    void messageDetailReturnsContentAndAudits() {
        when(sessionMapper.selectById(139L)).thenReturn(session(139L));
        ChatMessage message = message(7L, 139L, 2, 1, "完整正文", null, null);
        message.setModelCode("deepseek-v4-pro");
        message.setPromptTokens(2348);
        message.setCompletionTokens(120);
        when(messageMapper.selectById(7L)).thenReturn(message);

        AdminChatSessionDtos.MessageDetail detail = service.messageDetail(139L, 7L);

        assertThat(detail.contentText()).isEqualTo("完整正文");
        assertThat(detail.modelCode()).isEqualTo("deepseek-v4-pro");
        assertThat(detail.promptTokens()).isEqualTo(2348);
        verify(auditRecorder).record(eq("ai"), eq("ai_chat_audit_view"), any(), eq("chat_message"), eq(7L),
                eq("{\"sessionId\":139}"));
    }

    @Test
    @DisplayName("列表与预览不应写审计，避免翻页产生噪音")
    void listAndPreviewDoNotAudit() {
        when(sessionMapper.selectAdminPage(any(), any(), any(), any())).thenReturn(page(List.of(), 0));
        when(sessionMapper.selectById(139L)).thenReturn(session(139L));
        when(messageMapper.selectAdminPage(any(), eq(139L), any())).thenReturn(page(List.of(), 0));

        service.list(null, null, null, new PageQuery());
        service.messages(139L, null, new PageQuery());

        verify(auditRecorder, never()).record(any(), any(), any(), any(), anyLong(), any());
    }

    private static AdminSessionHit hit(long id, String title, int msgCount) {
        AdminSessionHit hit = new AdminSessionHit();
        hit.setId(id);
        hit.setUserId(2L);
        hit.setUserNickname("小明");
        hit.setTitle(title);
        hit.setSessionStatus(1);
        hit.setSessionType(1);
        hit.setModelCode("deepseek-v4-pro");
        hit.setSystemPromptVer("chat@v1");
        hit.setMsgCount(msgCount);
        hit.setHasSummary(1);
        hit.setLastMsgAt(LocalDateTime.of(2026, 11, 5, 10, 0));
        hit.setGmtCreate(LocalDateTime.of(2026, 11, 4, 9, 0));
        return hit;
    }

    private static SessionRoundCount roundCount(long sessionId, int rounds) {
        SessionRoundCount row = new SessionRoundCount();
        row.setSessionId(sessionId);
        row.setRounds(rounds);
        return row;
    }

    private static ChatSession session(long id) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(2L);
        session.setTitle("血糖咨询");
        session.setSessionStatus(1);
        return session;
    }

    private static ChatMessage message(long id, long sessionId, int role, int type,
                                       String contentText, String mediaCaption, String asrText) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setUserId(2L);
        message.setSeqNo((int) id);
        message.setMsgRole(role);
        message.setMsgType(type);
        message.setContentText(contentText);
        message.setMediaCaption(mediaCaption);
        message.setAsrText(asrText);
        message.setGmtCreate(LocalDateTime.of(2026, 11, 5, 10, 0));
        return message;
    }

    private static <T> IPage<T> page(List<T> records, long total) {
        Page<T> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }
}
