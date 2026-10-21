package com.threadfit.glucopilot.content.media;

import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.media.CmsMediaDtos.RegisterRequest;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CmsMediaServiceTest {
    private static final long OPS_ID = 9L;
    private static final String KEY = "5/2026/10/-9/" + "a".repeat(32) + ".png";

    @Mock private ObjectStorageService storage;
    @Mock private MediaFileMapper mediaFileMapper;
    @InjectMocks private CmsMediaService service;

    @Test
    void registersBannerImageUnderOpsIdentity() throws Exception {
        byte[] bytes = png();
        when(storage.objectExists(KEY)).thenReturn(true);
        when(storage.getObject(KEY)).thenReturn(bytes);
        when(storage.provider()).thenReturn(CodeEnum.StorageProvider.MINIO);
        when(storage.bucket()).thenReturn("glucopilot");
        when(mediaFileMapper.insert(any(MediaFile.class))).thenAnswer(invocation -> {
            invocation.<MediaFile>getArgument(0).setId(88L);
            return 1;
        });

        var result = service.register(request("banner", KEY, bytes.length), OPS_ID);

        assertEquals("88", result.getId());
        assertEquals("banner", result.getPurpose());
        assertEquals("/api/v1/cms/images/88", result.getFileUrl());
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper).updateById(captor.capture());
        assertEquals("CONTENT_BANNER", captor.getValue().getBizId());
        assertEquals(OPS_ID, captor.getValue().getOpsId());
    }

    @Test
    void rejectsForeignOpsKey() {
        assertThrows(BizException.class,
                () -> service.register(request("news", KEY.replace("/-9/", "/-8/"), 100), OPS_ID));
        verifyNoInteractions(storage, mediaFileMapper);
    }

    @Test
    void softDeletesOwnedMedia() {
        MediaFile row = new MediaFile();
        row.setId(3L);
        row.setBizType(CodeEnum.MediaBizType.CMS.getCode());
        row.setBizId("CONTENT_NEWS");
        row.setOpsId(OPS_ID);
        row.setFileStatus(1);
        when(mediaFileMapper.selectById(3L)).thenReturn(row);

        service.remove(3L, OPS_ID);

        assertEquals(0, row.getFileStatus());
        verify(mediaFileMapper).updateById(row);
    }

    @Test
    void rejectsDeleteForOtherOps() {
        MediaFile row = new MediaFile();
        row.setId(3L);
        row.setBizType(CodeEnum.MediaBizType.CMS.getCode());
        row.setBizId("CONTENT_NEWS");
        row.setOpsId(8L);
        row.setFileStatus(1);
        when(mediaFileMapper.selectById(3L)).thenReturn(row);

        assertThrows(BizException.class, () -> service.remove(3L, OPS_ID));
        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
    }

    @Test
    void registersNewsVideoUnderOpsIdentity() {
        String videoKey = "5/2026/10/-9/" + "b".repeat(32) + ".mp4";
        byte[] bytes = mp4();
        when(storage.objectExists(videoKey)).thenReturn(true);
        when(storage.getObject(videoKey)).thenReturn(bytes);
        when(storage.provider()).thenReturn(CodeEnum.StorageProvider.MINIO);
        when(storage.bucket()).thenReturn("glucopilot");
        when(mediaFileMapper.insert(any(MediaFile.class))).thenAnswer(invocation -> {
            invocation.<MediaFile>getArgument(0).setId(99L);
            return 1;
        });

        RegisterRequest req = request("news-video", videoKey, bytes.length);
        req.setMimeType("video/mp4");
        var result = service.register(req, OPS_ID);

        assertEquals("99", result.getId());
        assertEquals("news-video", result.getPurpose());
        assertEquals("/api/v1/cms/videos/99", result.getFileUrl());
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper).updateById(captor.capture());
        assertEquals("CONTENT_NEWS_VIDEO", captor.getValue().getBizId());
    }

    private static RegisterRequest request(String purpose, String key, long size) {
        RegisterRequest request = new RegisterRequest();
        request.setPurpose(purpose);
        request.setBizType(5);
        request.setStorageKey(key);
        request.setMimeType("image/png");
        request.setSizeBytes(size);
        return request;
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Minimal ISO BMFF with "ftyp" at offset 4. */
    private static byte[] mp4() {
        byte[] bytes = new byte[32];
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        return bytes;
    }
}
