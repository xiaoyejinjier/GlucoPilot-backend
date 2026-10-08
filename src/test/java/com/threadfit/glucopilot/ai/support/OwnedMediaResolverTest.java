package com.threadfit.glucopilot.ai.support;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 媒体归属规则的回归测试。
 * <p>
 * 这条规则是「多模态上线前必须先修」的那一项：图片消息引用他人 mediaId 时，
 * 只有文本模型的阶段后果是坏引用，接上视觉模型后会变成
 * 「把他人图片读出来描述给当前用户」——实质性越权内容泄露。
 * 规则集中在 {@link OwnedMediaResolver}，因此拒绝行为的断言也应当集中在这里。
 */
@ExtendWith(MockitoExtension.class)
class OwnedMediaResolverTest {

    private static final Long USER_ID = 1L;
    private static final Long MEDIA_ID = 123L;

    @Mock
    private MediaFileService mediaFileService;

    @InjectMocks
    private OwnedMediaResolver resolver;

    @Test
    void 归属匹配时应返回媒体记录() {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        when(mediaFileService.findOwnedMedia(MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE)).thenReturn(media);

        assertSame(media, resolver.require(MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE));
    }

    @Test
    void 查询应带上调用方声明的业务类型() {
        when(mediaFileService.findOwnedMedia(MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE))
                .thenReturn(new MediaFile());

        resolver.require(MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE);

        // 业务类型错了就会把餐食照片之类当成聊天图放进来，因此这里必须逐个校验
        verify(mediaFileService).findOwnedMedia(MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE);
    }

    @Test
    void 查不到时应拒绝且文案不区分不存在与不是你的() {
        when(mediaFileService.findOwnedMedia(MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> resolver.require(MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE));

        assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
        // 区分「不存在」与「不是你的」等于提供了一个探测接口，可以枚举他人 mediaId 是否存在
        assertEquals("图片文件不存在或不可用", ex.getMessage());
    }

    @Test
    void 语音与图片的文案应各自准确() {
        when(mediaFileService.findOwnedMedia(MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> resolver.require(MEDIA_ID, USER_ID, MediaBizType.CHAT_VOICE));

        assertEquals("语音文件不存在或不可用", ex.getMessage());
    }

    @Test
    void mediaId为空时应直接拒绝且不查库() {
        BizException ex = assertThrows(BizException.class,
                () -> resolver.require(null, USER_ID, MediaBizType.CHAT_IMAGE));

        assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
        // 空值不该被当成「拥有」，也不该白跑一条 SQL
        verifyNoInteractions(mediaFileService);
    }
}
