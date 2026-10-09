package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.support.OwnedMediaResolver;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.ChatMsgType;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2.24 写用户消息时对媒体引用的处置。
 * <p>
 * <b>注意职责边界</b>：「不属于当前用户就拒绝」这条规则本身由 {@link OwnedMediaResolver} 实现，
 * 它的拒绝行为在 {@code OwnedMediaResolverTest} 里断言。这里只负责两件属于本类的事：
 * <ol>
 *   <li>图片 / 语音消息<b>确实走了</b>归属校验，且业务类型传对了（传错类型会把餐食照片当聊天图放进来）；</li>
 *   <li>校验不通过时<b>不落库</b>，以及文本消息一次查询都不该发。</li>
 * </ol>
 * 这样拆分之后，将来改归属规则只需要改一处、测一处。
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 9L;
    private static final Long IMAGE_MEDIA_ID = 123L;
    private static final Long VOICE_MEDIA_ID = 77L;

    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private ChatSessionService chatSessionService;
    @Mock
    private OwnedMediaResolver ownedMediaResolver;

    @InjectMocks
    private ChatMessageService service;

    // ---------- 校验必须生效 ----------

    @Test
    void 图片消息应按CHAT_IMAGE类型校验归属并通过() {
        when(ownedMediaResolver.require(IMAGE_MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE))
                .thenReturn(media(IMAGE_MEDIA_ID));
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(3);

        service.appendUserMessage(USER_ID, SESSION_ID, imageRequest(IMAGE_MEDIA_ID));

        verify(ownedMediaResolver).require(IMAGE_MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE);
        verify(chatMessageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void 语音消息应按CHAT_VOICE类型校验归属并通过() {
        when(ownedMediaResolver.require(VOICE_MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE))
                .thenReturn(media(VOICE_MEDIA_ID));
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(2);

        service.appendUserMessage(USER_ID, SESSION_ID, voiceRequest(VOICE_MEDIA_ID));

        verify(ownedMediaResolver).require(VOICE_MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE);
        verify(chatMessageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void 归属校验不通过时应阻止落库() {
        when(ownedMediaResolver.require(IMAGE_MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE))
                .thenThrow(new BizException(ErrorCode.BIZ_REJECTED, "图片文件不存在或不可用"));

        BizException ex = assertThrows(BizException.class,
                () -> service.appendUserMessage(USER_ID, SESSION_ID, imageRequest(IMAGE_MEDIA_ID)));

        assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
        // 校验失败绝不能留下一条引用他人媒体的消息
        verify(chatMessageMapper, never()).insert(any(ChatMessage.class));
    }

    // ---------- 正常路径不能被误伤 ----------

    @Test
    void 文本消息不应触发媒体归属校验() {
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(1);

        service.appendUserMessage(USER_ID, SESSION_ID, textRequest("早餐前血糖 6.8 正常吗"));

        // 文本消息没有任何媒体引用，多一次查询就是白花的开销
        verifyNoInteractions(ownedMediaResolver);
        verify(chatMessageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void 图片消息缺少mediaId应返回参数错误且不进入归属校验() {
        BizException ex = assertThrows(BizException.class,
                () -> service.appendUserMessage(USER_ID, SESSION_ID, imageRequest(null)));

        // 必填性与归属是两个不同的问题，报错也要分开，否则前端无法区分
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("图片消息 mediaId 不能为空", ex.getMessage());
        verifyNoInteractions(ownedMediaResolver);
    }

    // ---------- 并发：助手回复的唯一性与顺序号 ----------

    @Test
    void 助手消息应写入确定性的clientMsgId使唯一键生效() {
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(2);
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);

        service.appendAssistantMessage(USER_ID, SESSION_ID, 42L, "答案", "test-model", 1, 2, 1, 10);

        verify(chatMessageMapper).insert(captor.capture());
        // 唯一键 uk_session_client_active 对 NULL 不生效（唯一索引允许多个 NULL），
        // 所以助手消息必须有一个确定性的值，"同一轮至多一条回复"才是数据库保证的
        assertEquals("assistant:42", captor.getValue().getClientMsgId());
    }

    @Test
    void 助手回复撞唯一键时应回查既有回复而不是报错() {
        ChatMessage winner = new ChatMessage();
        winner.setId(88L);
        winner.setReplyToMsgId(42L);
        winner.setContentText("并发方先落库的回答");

        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(2);
        when(chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(duplicateKey("chat_message.uk_session_client_active"));
        when(chatMessageMapper.selectAssistantReply(SESSION_ID, 42L)).thenReturn(winner);

        ChatMessage result = service.appendAssistantMessage(
                USER_ID, SESSION_ID, 42L, "本请求的回答", "test-model", 1, 2, 1, 10);

        // 两个客户端最终收敛到同一条回答（幂等语义与用户消息路径一致）
        assertEquals(88L, result.getId());
        assertEquals("并发方先落库的回答", result.getContentText());
        // 没有新行落库，就不该再动会话的消息计数
        verify(chatSessionMapper, never()).touchOnMessage(anyLong(), any());
    }

    @Test
    void 助手回复撞唯一键却查不到既有行时应抛出() {
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(2);
        when(chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(duplicateKey("chat_message.uk_session_client_active"));
        when(chatMessageMapper.selectAssistantReply(SESSION_ID, 42L)).thenReturn(null);

        assertThrows(DuplicateKeyException.class, () -> service.appendAssistantMessage(
                USER_ID, SESSION_ID, 42L, "答案", "test-model", 1, 2, 1, 10));
    }

    @Test
    void 顺序号冲突时应重算并重试成功() {
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(7, 8);
        when(chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(duplicateKey("chat_message.uk_session_seq"))
                .thenReturn(1);

        ChatMessage saved = service.appendAssistantMessage(
                USER_ID, SESSION_ID, 42L, "答案", "test-model", 1, 2, 1, 10);

        assertEquals(8, saved.getSeqNo(), "重试时必须重新取号");
        verify(chatMessageMapper, times(2)).insert(any(ChatMessage.class));
    }

    @Test
    void 顺序号连续冲突超过上限应抛出() {
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(7);
        when(chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(duplicateKey("chat_message.uk_session_seq"));

        assertThrows(DuplicateKeyException.class, () -> service.appendAssistantMessage(
                USER_ID, SESSION_ID, 42L, "答案", "test-model", 1, 2, 1, 10));

        // 重试是有上限的：无限重试会把请求挂死，也会掩盖真正的问题
        verify(chatMessageMapper, times(3)).insert(any(ChatMessage.class));
    }

    @Test
    void 用户消息顺序号冲突应重试而不是被当成重复提交() {
        when(chatMessageMapper.selectNextSeqNo(SESSION_ID)).thenReturn(1, 2);
        when(chatMessageMapper.insert(any(ChatMessage.class)))
                .thenThrow(duplicateKey("chat_message.uk_session_seq"))
                .thenReturn(1);

        service.appendUserMessage(USER_ID, SESSION_ID, textRequest("早餐前血糖 6.8 正常吗"));

        // 撞号走重试；绝不能落到"重复 clientMsgId 回查"那条分支上去（那里查不到东西）
        verify(chatMessageMapper, never()).selectByClientMsgId(anyLong(), anyString());
        verify(chatMessageMapper, times(2)).insert(any(ChatMessage.class));
    }

    // ---------- 构造辅助 ----------

    /** 模拟 MySQL 1062：消息里带上键名，供 SqlDuplicateKeys 分流。 */
    private static DuplicateKeyException duplicateKey(String keyName) {
        return new DuplicateKeyException("duplicate",
                new java.sql.SQLException("Duplicate entry 'x' for key '" + keyName + "'", "23000", 1062));
    }

    private static ChatDtos.SendMessageRequest textRequest(String contentText) {
        ChatDtos.SendMessageRequest request = new ChatDtos.SendMessageRequest();
        request.setClientMsgId("c-text");
        request.setMsgType(ChatMsgType.TEXT.getCode());
        request.setContentText(contentText);
        return request;
    }

    private static ChatDtos.SendMessageRequest imageRequest(Long mediaId) {
        ChatDtos.SendMessageRequest request = new ChatDtos.SendMessageRequest();
        request.setClientMsgId("c-image");
        request.setMsgType(ChatMsgType.IMAGE.getCode());
        request.setMediaId(mediaId);
        return request;
    }

    private static ChatDtos.SendMessageRequest voiceRequest(Long voiceMediaId) {
        ChatDtos.SendMessageRequest request = new ChatDtos.SendMessageRequest();
        request.setClientMsgId("c-voice");
        request.setMsgType(ChatMsgType.VOICE.getCode());
        request.setVoiceMediaId(voiceMediaId);
        return request;
    }

    private static MediaFile media(Long id) {
        MediaFile media = new MediaFile();
        media.setId(id);
        return media;
    }
}
