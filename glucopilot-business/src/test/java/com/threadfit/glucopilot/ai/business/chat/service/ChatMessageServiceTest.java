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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    // ---------- 构造辅助 ----------

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
