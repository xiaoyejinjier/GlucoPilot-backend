package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.business.chat.service.impl.ChatSessionServiceImpl;

/**
 * 2.23 get-or-create 的并发语义。
 * <p>
 * 关键在于：**"每用户至多一条进行中会话"不能靠开头的 {@code selectActiveByUserId}**——
 * 那是普通快照读，两个并发请求都会查不到，然后各建一条（已实测复现）。
 * 权威判据是唯一键 {@code uk_user_active}，落败方捕获 1062 后回查并发方刚建好的那条。
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    private static final Long USER_ID = 17L;
    private static final Long SESSION_TYPE = 3L;

    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper chatMessageMapper;
    @Mock
    private AiChatClientRegistry chatClientRegistry;
    @Mock
    private AiPromptTemplateService promptTemplateService;

    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionServiceImpl(chatSessionMapper, chatMessageMapper, new AIProperties(),
                chatClientRegistry, promptTemplateService, org.mockito.Mockito.mock(com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService.class));
    }

    @Test
    void 已有进行中会话时应直接返回且不再插入() {
        ChatSession existing = session(48L);
        when(chatSessionMapper.selectActiveByUserId(USER_ID)).thenReturn(existing);

        ChatSession result = service.getOrCreate(USER_ID, 3);

        assertEquals(48L, result.getId());
        verify(chatSessionMapper, never()).insert(any(ChatSession.class));
    }

    @Test
    void 新建会话应落模型与提示词版本标签() {
        when(chatSessionMapper.selectActiveByUserId(USER_ID)).thenReturn(null);
        when(chatClientRegistry.modelCode(SceneCode.CHAT)).thenReturn("test-model");
        when(promptTemplateService.versionTag(SceneCode.CHAT)).thenReturn("chat@v1");
        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);

        service.getOrCreate(USER_ID, null);

        verify(chatSessionMapper).insert(captor.capture());
        ChatSession inserted = captor.getValue();
        assertEquals(CodeEnum.ChatSessionStatus.ACTIVE.getCode(), inserted.getSessionStatus());
        // 类型为空时落"混合"，与接口默认值一致
        assertEquals(CodeEnum.ChatSessionType.MIXED.getCode(), inserted.getSessionType());
        assertEquals("test-model", inserted.getModelCode());
        assertEquals("chat@v1", inserted.getSystemPromptVer());
        assertEquals("新对话", inserted.getTitle());
    }

    @Test
    void 并发建会话落败时应复用并发方建好的会话() {
        ChatSession winner = session(49L);
        // 第一次查不到（并发方还没提交），插入撞唯一键，回查才拿到
        when(chatSessionMapper.selectActiveByUserId(USER_ID)).thenReturn(null, winner);
        when(chatClientRegistry.modelCode(SceneCode.CHAT)).thenReturn("test-model");
        when(promptTemplateService.versionTag(SceneCode.CHAT)).thenReturn("chat@v1");
        when(chatSessionMapper.insert(any(ChatSession.class))).thenThrow(duplicateKey("uk_user_active"));

        ChatSession result = service.getOrCreate(USER_ID, 3);

        // 两个请求必须拿到同一个 sessionId，否则用户消息会进 A 会话、助手回复进 B 会话
        assertEquals(49L, result.getId());
        verify(chatSessionMapper, times(2)).selectActiveByUserId(USER_ID);
    }

    @Test
    void 撞唯一键但回查不到会话时应抛出而不是返回未落库的对象() {
        when(chatSessionMapper.selectActiveByUserId(USER_ID)).thenReturn(null);
        when(chatClientRegistry.modelCode(SceneCode.CHAT)).thenReturn("test-model");
        when(promptTemplateService.versionTag(SceneCode.CHAT)).thenReturn("chat@v1");
        when(chatSessionMapper.insert(any(ChatSession.class))).thenThrow(duplicateKey("uk_user_active"));

        assertThrows(DuplicateKeyException.class, () -> service.getOrCreate(USER_ID, 3));
    }

    @Test
    void 其它唯一键冲突不应被当成并发建会话() {
        when(chatSessionMapper.selectActiveByUserId(USER_ID)).thenReturn(null);
        when(chatClientRegistry.modelCode(SceneCode.CHAT)).thenReturn("test-model");
        when(promptTemplateService.versionTag(SceneCode.CHAT)).thenReturn("chat@v1");
        when(chatSessionMapper.insert(any(ChatSession.class))).thenThrow(duplicateKey("some_other_key"));

        assertThrows(DuplicateKeyException.class, () -> service.getOrCreate(USER_ID, 3));
        // 不是熟悉的键就不要再回查一次（多一次查询掩盖不了问题，只会让现场更难判读）
        verify(chatSessionMapper, times(1)).selectActiveByUserId(USER_ID);
    }

    private static ChatSession session(Long id) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(USER_ID);
        return session;
    }

    private static DuplicateKeyException duplicateKey(String keyName) {
        return new DuplicateKeyException("duplicate",
                new SQLException("Duplicate entry 'x' for key 'chat_session." + keyName + "'", "23000", 1062));
    }
}
