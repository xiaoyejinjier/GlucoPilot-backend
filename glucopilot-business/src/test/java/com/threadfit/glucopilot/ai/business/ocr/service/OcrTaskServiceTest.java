package com.threadfit.glucopilot.ai.business.ocr.service;

import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos;
import com.threadfit.glucopilot.ai.business.ocr.entity.OcrTask;
import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.business.ocr.service.impl.OcrTaskServiceImpl;

class OcrTaskServiceTest {

    private final OcrTaskMapper taskMapper = mock(OcrTaskMapper.class);
    private final MediaFileService mediaFileService = mock(MediaFileService.class);
    private final VisionAnalyzer visionAnalyzer = mock(VisionAnalyzer.class);
    private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
    private final IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
    private final Executor directExecutor = Runnable::run;

    private OcrTaskService service;

    @BeforeEach
    void setUp() {
        service = new OcrTaskServiceImpl(taskMapper, mediaFileService, visionAnalyzer,
                jsonUtils, idempotencyStore, directExecutor);
    }

    @Test
    void createsTaskAndStoresNormalizedRecognitionResult() {
        MediaFile media = glucoseMedia(1001L);
        when(mediaFileService.findOwnedMedia(1001L, 7L, CodeEnum.MediaBizType.GLUCOSE))
                .thenReturn(media);
        when(taskMapper.insert(any(OcrTask.class))).thenAnswer(invocation -> {
            OcrTask row = invocation.getArgument(0);
            row.setId(88L);
            return 1;
        });
        when(visionAnalyzer.analyze(eq(media), eq(SceneCode.OCR_GLUCOSE), any()))
                .thenReturn("```json\n{\"schemaVersion\":1,\"bizType\":1,"
                        + "\"suggestedValue\":6.24,\"confidence\":110,\"rawText\":\"6.2 mmol/L\"}\n```");

        OcrDtos.TaskView created = service.create(request(1001L, 1), 7L);

        assertEquals("88", created.getId());
        assertEquals(CodeEnum.TaskStatus.PENDING.getCode(), created.getTaskStatus());

        ArgumentCaptor<OcrTask> updates = ArgumentCaptor.forClass(OcrTask.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).updateById(updates.capture());
        OcrTask finished = updates.getAllValues().get(1);
        assertEquals(CodeEnum.TaskStatus.SUCCEEDED.getCode(), finished.getTaskStatus());
        OcrDtos.GlucoseResult result = jsonUtils.fromJson(finished.getOcrResultJson(), OcrDtos.GlucoseResult.class);
        assertEquals(new BigDecimal("6.2"), result.getSuggestedValue());
        assertEquals(100, result.getConfidence());
    }

    @Test
    void outOfRangeModelValueBecomesUnreadableInsteadOfBeingSaved() {
        MediaFile media = glucoseMedia(1001L);
        when(mediaFileService.findOwnedMedia(1001L, 7L, CodeEnum.MediaBizType.GLUCOSE))
                .thenReturn(media);
        when(taskMapper.insert(any(OcrTask.class))).thenAnswer(invocation -> {
            OcrTask row = invocation.getArgument(0);
            row.setId(89L);
            return 1;
        });
        when(visionAnalyzer.analyze(eq(media), eq(SceneCode.OCR_GLUCOSE), any()))
                .thenReturn("{\"schemaVersion\":1,\"bizType\":1,"
                        + "\"suggestedValue\":99,\"confidence\":95,\"rawText\":\"99\"}");

        service.create(request(1001L, 1), 7L);

        ArgumentCaptor<OcrTask> updates = ArgumentCaptor.forClass(OcrTask.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).updateById(updates.capture());
        OcrDtos.GlucoseResult result = jsonUtils.fromJson(
                updates.getAllValues().get(1).getOcrResultJson(), OcrDtos.GlucoseResult.class);
        assertNull(result.getSuggestedValue());
        assertTrue(result.getConfidence() <= 30);
    }

    @Test
    void rejectsMealOcrAndForeignMedia() {
        assertThrows(BizException.class, () -> service.create(request(1001L, 2), 7L));
        assertThrows(BizException.class, () -> service.create(request(1001L, 1), 7L));
    }

    private static OcrDtos.CreateRequest request(Long mediaId, int bizType) {
        OcrDtos.CreateRequest request = new OcrDtos.CreateRequest();
        request.setMediaId(mediaId);
        request.setBizType(bizType);
        return request;
    }

    private static MediaFile glucoseMedia(Long id) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(7L);
        media.setBizType(CodeEnum.MediaBizType.GLUCOSE.getCode());
        media.setFileStatus(1);
        media.setStorageKey("2/2026/10/7/image.jpg");
        media.setMimeType("image/jpeg");
        media.setSizeBytes(1024L);
        return media;
    }
}
