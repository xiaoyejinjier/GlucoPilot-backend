package com.threadfit.glucopilot.food.service;

import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.dto.FileDtos;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodImageServiceTest {
    private static final long OPS_ID = 9L;
    private static final String KEY = "5/2026/10/-9/" + "a".repeat(32) + ".png";

    @Mock private ObjectStorageService storage;
    @Mock private MediaFileMapper mediaFileMapper;
    @InjectMocks private FoodImageService service;

    @Test
    void registersRealImageUnderOpsIdentityAndReturnsStablePath() throws Exception {
        byte[] bytes = png();
        when(storage.objectExists(KEY)).thenReturn(true);
        when(storage.getObject(KEY)).thenReturn(bytes);
        when(storage.provider()).thenReturn(CodeEnum.StorageProvider.MINIO);
        when(mediaFileMapper.insert(any(MediaFile.class))).thenAnswer(invocation -> {
            invocation.<MediaFile>getArgument(0).setId(72L);
            return 1;
        });

        var result = service.register(request(KEY, bytes.length, "image/png"), OPS_ID);

        assertEquals("72", result.getId());
        assertEquals("/api/v1/foods/images/72", result.getFileUrl());
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper).updateById(captor.capture());
        MediaFile row = captor.getValue();
        assertNull(row.getUserId());
        assertEquals(OPS_ID, row.getOpsId());
        assertEquals("FOOD_IMAGE", row.getBizId());
        assertEquals(2, row.getWidthPx());
        assertEquals(3, row.getHeightPx());
        assertEquals(64, row.getSha256Hex().length());
    }

    @Test
    void rejectsAnotherOperatorsKeyBeforeReadingStorage() {
        assertThrows(BizException.class,
                () -> service.register(request(KEY.replace("/-9/", "/-8/"), 100, "image/png"), OPS_ID));
        verifyNoInteractions(storage, mediaFileMapper);
    }

    @Test
    void rejectsClientUserKeyBeforeReadingStorage() {
        assertThrows(BizException.class,
                () -> service.register(request(KEY.replace("/-9/", "/9/"), 100, "image/png"), OPS_ID));
        verifyNoInteractions(storage, mediaFileMapper);
    }

    @Test
    void rejectsInvalidImageEvenWhenClientClaimsPng() {
        byte[] bytes = "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.objectExists(KEY)).thenReturn(true);
        when(storage.getObject(KEY)).thenReturn(bytes);
        assertThrows(BizException.class,
                () -> service.register(request(KEY, bytes.length, "image/png"), OPS_ID));
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
    }

    @Test
    void rejectsPngContentDisguisedAsJpeg() throws Exception {
        String jpgKey = KEY.replace(".png", ".jpg");
        byte[] bytes = png();
        when(storage.objectExists(jpgKey)).thenReturn(true);
        when(storage.getObject(jpgKey)).thenReturn(bytes);
        assertThrows(BizException.class,
                () -> service.register(request(jpgKey, bytes.length, "image/jpeg"), OPS_ID));
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
    }

    @Test
    void reusesExistingRegistrationWhenSameObjectIsRetried() {
        MediaFile row = image();
        when(storage.objectExists(KEY)).thenReturn(true);
        when(mediaFileMapper.selectOne(any())).thenReturn(row);
        assertEquals("72", service.register(request(KEY, 100, "image/png"), OPS_ID).getId());
        verify(storage, never()).getObject(anyString());
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
    }

    @Test
    void preventsPublicAccessToUserHealthPhotos() {
        MediaFile row = image();
        row.setUserId(42L);
        when(mediaFileMapper.selectById(72L)).thenReturn(row);
        assertThrows(BizException.class, () -> service.requireImage(72L));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsOversizedUploadsAndUnsupportedSvg() {
        FileDtos.UploadCredentialRequest request = new FileDtos.UploadCredentialRequest();
        request.setBizType(5);
        request.setMimeType("image/png");
        request.setSizeBytes(5L * 1024 * 1024 + 1);
        assertThrows(BizException.class, () -> service.credentials(request, OPS_ID));
        request.setSizeBytes(100L);
        request.setMimeType("image/svg+xml");
        assertThrows(BizException.class, () -> service.credentials(request, OPS_ID));
        verifyNoInteractions(storage);
    }

    private static FileDtos.RegisterRequest request(String key, int size, String mime) {
        FileDtos.RegisterRequest request = new FileDtos.RegisterRequest();
        request.setBizType(5);
        request.setStorageKey(key);
        request.setMimeType(mime);
        request.setSizeBytes((long) size);
        return request;
    }

    private static MediaFile image() {
        MediaFile row = new MediaFile();
        row.setId(72L);
        row.setOpsId(OPS_ID);
        row.setBizType(5);
        row.setBizId("FOOD_IMAGE");
        row.setFileStatus(1);
        row.setMimeType("image/png");
        return row;
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
