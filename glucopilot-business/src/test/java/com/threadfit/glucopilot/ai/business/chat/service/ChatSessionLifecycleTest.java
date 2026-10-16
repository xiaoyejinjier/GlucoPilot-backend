package com.threadfit.glucopilot.ai.business.chat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.AiPromptTemplateService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话生命周期闭环（5.3 列表 / 5.4 删除 / 2.23 新建 / 置顶 / 状态校验）。
 * <p>
 * 这里守的几条都是"出错时用户直接能感觉到"的：
 * <ol>
 *   <li><b>新建会话必须先结束旧的</b>：{@code uk_user_active} 建在生成列上，顺序反了就撞唯一键；</li>
 *   <li><b>删除必须三态可分</b>：不存在 → 404、别人的 → 404、自己的已删 → 幂等成功；
 *       把"别人的会话"当成功返回等于把 404/200 变成一条探测 ID 是否存在的信道；</li>
 *   <li><b>惰性归档不能拖垮列表</b>：归档语句失败只记日志，列表照常返回；</li>
 *   <li><b>已结束/已归档的会话不能再写</b>，否则消息进了老会话、/current 却指着新会话。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionLifecycleTest {

    private static final Long USER_ID = 17L;
    private static final Long SESSION_ID = 48L;

    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private AiChatClientRegistry chatClientRegistry;
    @Mock
    private AiPromptTemplateService promptTemplateService;

    private AIProperties properties;

    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        properties = new AIProperties();
        service = new ChatSessionService(chatSessionMapper, chatMessageMapper, properties,
                chatClientRegistry, promptTemplateService);
    }

    private static ChatSession session(Long id, int status, boolean deleted, boolean pinned) {
        ChatSession row = new ChatSession();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setTitle("新对话");
        row.setSessionStatus(status);
        row.setSessionType(3);
        row.setMsgCount(4);
        row.setIsPinned(pinned ? 1 : 0);
        row.setIsDeleted(deleted ? 1 : 0);
        row.setLastMsgAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        return row;
    }

    // ---------------------------------------------------------------- 5.3 列表

    @Test
    void 列表按置顶优先并带回最后一条消息预览() {
        ChatSession row = session(SESSION_ID, 1, false, true);
        when(chatSessionMapper.selectPage(any(), any())).thenReturn(page(1, 20, List.of(row)));
        when(chatMessageMapper.selectLastMessagePerSession(any())).thenReturn(
                List.of(Map.of("sessionId", SESSION_ID, "preview", "早餐前血糖 6.8 正常吗")));

        ChatDtos.SessionListResponse response = service.list(USER_ID, null, 1, null);

        assertEquals(1L, response.getTotal());
        assertEquals(1, response.getRecords().size());
        ChatDtos.SessionRow item = response.getRecords().get(0);
        assertEquals("早餐前血糖 6.8 正常吗", item.getLastMessagePreview());
        assertTrue(item.getIsPinned());
        assertEquals(30, response.getArchiveIdleDays());
        // 预览必须批量取（一次一条 IN 查询），不能每行查一次
        verify(chatMessageMapper, times(1)).selectLastMessagePerSession(any());
    }

    @Test
    void 列表会先做惰性归档并且把阈值按天换算() {
        when(chatSessionMapper.selectPage(any(), any())).thenReturn(page(1, 20, List.of()));
        properties.getChat().setArchiveIdleDays(7);

        service.list(USER_ID, 1, 1, 20);

        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chatSessionMapper).archiveIdleSessions(eq(USER_ID), before.capture());
        long days = java.time.Duration.between(before.getValue(), LocalDateTime.now()).toDays();
        assertEquals(7, days, "归档阈值必须是 now - 7 天");
    }

    @Test
    void 归档语句失败时列表照常返回() {
        when(chatSessionMapper.archiveIdleSessions(anyLong(), any()))
                .thenThrow(new IllegalStateException("deadlock"));
        ChatSession row = session(SESSION_ID, 1, false, false);
        when(chatSessionMapper.selectPage(any(), any())).thenReturn(page(1, 20, List.of(row)));
        when(chatMessageMapper.selectLastMessagePerSession(any())).thenReturn(List.of());

        ChatDtos.SessionListResponse response = service.list(USER_ID, null, 1, 20);

        assertEquals(1, response.getRecords().size(), "归档只是展示语义，失败不能拖垮列表");
        assertNull(response.getRecords().get(0).getLastMessagePreview());
    }

    @Test
    void 归档关闭时不发归档语句() {
        properties.getChat().setArchiveIdleDays(0);
        when(chatSessionMapper.selectPage(any(), any())).thenReturn(page(1, 20, List.of()));

        service.list(USER_ID, null, 1, 20);

        verify(chatSessionMapper, never()).archiveIdleSessions(anyLong(), any());
    }

    @Test
    void 列表页大小被上限夹住() {
        properties.getChat().setMaxPageSize(50);
        when(chatSessionMapper.selectPage(any(), any())).thenReturn(page(1, 50, List.of()));

        service.list(USER_ID, null, 1, 100000);

        ArgumentCaptor<Page<ChatSession>> captor = ArgumentCaptor.forClass(Page.class);
        verify(chatSessionMapper).selectPage(captor.capture(), any());
        assertEquals(50L, captor.getValue().getSize(), "超大 pageSize 必须被夹到上限");
    }

    // ---------------------------------------------------------------- 2.23 新建

    @Test
    void 新建会话必须先结束旧会话再插入() {
        when(chatSessionMapper.endActiveSessions(USER_ID)).thenReturn(1);
        when(chatClientRegistry.modelCode(SceneCode.CHAT)).thenReturn("deepseek-v4-pro");
        when(promptTemplateService.versionTag(SceneCode.CHAT)).thenReturn("chat@v2");

        ChatSession created = service.openNew(USER_ID, 3);

        InOrder inOrder = inOrder(chatSessionMapper);
        inOrder.verify(chatSessionMapper).endActiveSessions(USER_ID);
        inOrder.verify(chatSessionMapper).insert(any(ChatSession.class));
        assertEquals("deepseek-v4-pro", created.getModelCode());
        assertEquals("chat@v2", created.getSystemPromptVer());
        assertEquals(1, created.getSessionStatus());
    }

    @Test
    void 新建会话撞唯一键时回查并发方建好的那条() {
        when(chatSessionMapper.endActiveSessions(USER_ID)).thenReturn(0);
        when(chatSessionMapper.insert(any(ChatSession.class))).thenThrow(duplicateOf(
                ChatSessionMapper.UK_USER_ACTIVE));
        ChatSession winner = session(99L, 1, false, false);
        when(chatSessionMapper.selectActiveByUserId(USER_ID)).thenReturn(winner);

        ChatSession result = service.openNew(USER_ID, 3);

        assertEquals(99L, result.getId(), "并发的两个新建请求应收敛到同一条会话");
    }

    @Test
    void 新建会话撞别的唯一键要原样抛出() {
        when(chatSessionMapper.endActiveSessions(USER_ID)).thenReturn(0);
        when(chatSessionMapper.insert(any(ChatSession.class)))
                .thenThrow(duplicateOf("uk_something_else"));

        assertThrows(DuplicateKeyException.class, () -> service.openNew(USER_ID, 3));
    }

    // ---------------------------------------------------------------- 5.4 删除

    @Test
    void 删除会同时逻辑删除会话与其消息() {
        when(chatSessionMapper.selectByIdIncludingDeleted(SESSION_ID)).thenReturn(session(SESSION_ID, 1, false, false));
        when(chatMessageMapper.logicDeleteBySession(SESSION_ID, USER_ID)).thenReturn(6);
        when(chatSessionMapper.logicDeleteSession(SESSION_ID, USER_ID)).thenReturn(1);

        service.delete(USER_ID, SESSION_ID);

        verify(chatMessageMapper).logicDeleteBySession(SESSION_ID, USER_ID);
        verify(chatSessionMapper).logicDeleteSession(SESSION_ID, USER_ID);
    }

    @Test
    void 重复删除幂等成功且不再删消息() {
        when(chatSessionMapper.selectByIdIncludingDeleted(SESSION_ID)).thenReturn(session(SESSION_ID, 2, true, false));

        service.delete(USER_ID, SESSION_ID);

        verify(chatMessageMapper, never()).logicDeleteBySession(anyLong(), any());
    }

    @Test
    void 删除不存在的会话返回404() {
        when(chatSessionMapper.selectByIdIncludingDeleted(404L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.delete(USER_ID, 404L));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void 删除别人的会话返回404且什么都不删() {
        ChatSession others = session(SESSION_ID, 1, false, false);
        others.setUserId(999L);
        when(chatSessionMapper.selectByIdIncludingDeleted(SESSION_ID)).thenReturn(others);

        BizException error = assertThrows(BizException.class, () -> service.delete(USER_ID, SESSION_ID));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(chatSessionMapper, never()).logicDeleteSession(anyLong(), anyLong());
        verify(chatMessageMapper, never()).logicDeleteBySession(anyLong(), any());
    }

    @Test
    void 并发下会话已被删掉时按幂等成功处理() {
        when(chatSessionMapper.selectByIdIncludingDeleted(SESSION_ID)).thenReturn(session(SESSION_ID, 1, false, false));
        when(chatMessageMapper.logicDeleteBySession(SESSION_ID, USER_ID)).thenReturn(0);
        when(chatSessionMapper.logicDeleteSession(SESSION_ID, USER_ID)).thenReturn(0);

        service.delete(USER_ID, SESSION_ID);

        // 不抛异常即为通过：消息已删，会话被别人先删了，重试不该弹错
        verify(chatSessionMapper).logicDeleteSession(SESSION_ID, USER_ID);
    }

    // ---------------------------------------------------------------- 置顶

    @Test
    void 置顶与取消置顶() {
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(SESSION_ID, 1, false, false));
        when(chatSessionMapper.updatePinned(SESSION_ID, USER_ID, 1)).thenReturn(1);

        service.setPinned(USER_ID, SESSION_ID, true);
        verify(chatSessionMapper).updatePinned(SESSION_ID, USER_ID, 1);

        when(chatSessionMapper.updatePinned(SESSION_ID, USER_ID, 0)).thenReturn(1);
        service.setPinned(USER_ID, SESSION_ID, false);
        verify(chatSessionMapper).updatePinned(SESSION_ID, USER_ID, 0);
    }

    @Test
    void 置顶不存在的会话返回404() {
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.setPinned(USER_ID, SESSION_ID, true));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(chatSessionMapper, never()).updatePinned(anyLong(), anyLong(), anyInt());
    }

    // ---------------------------------------------------------------- 状态校验

    @Test
    void 进行中的会话可以继续写() {
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(SESSION_ID, 1, false, false));

        assertEquals(SESSION_ID, service.requireActive(USER_ID, SESSION_ID).getId());
    }

    @Test
    void 已结束与会话归档都不能继续写且文案可区分() {
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(SESSION_ID, 2, false, false));
        BizException ended = assertThrows(BizException.class, () -> service.requireActive(USER_ID, SESSION_ID));
        assertEquals(ErrorCode.BIZ_REJECTED, ended.getErrorCode());
        assertTrue(ended.getMessage().contains("已结束"));

        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(SESSION_ID, 0, false, false));
        BizException archived = assertThrows(BizException.class, () -> service.requireActive(USER_ID, SESSION_ID));
        assertTrue(archived.getMessage().contains("已归档"));
    }

    @Test
    void 状态校验同样挡住别人的会话() {
        ChatSession others = session(SESSION_ID, 1, false, false);
        others.setUserId(999L);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(others);

        BizException error = assertThrows(BizException.class, () -> service.requireActive(USER_ID, SESSION_ID));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    // ---------------------------------------------------------------- 辅助

    private static Page<ChatSession> page(long current, long size, List<ChatSession> rows) {
        Page<ChatSession> page = new Page<>(current, size);
        page.setRecords(rows);
        page.setTotal(rows.size());
        return page;
    }

    private static DuplicateKeyException duplicateOf(String constraint) {
        SQLException sql = new SQLException("Duplicate entry 'x' for key '" + constraint + "'", "23000", 1062);
        return new DuplicateKeyException("duplicate", sql);
    }
}
