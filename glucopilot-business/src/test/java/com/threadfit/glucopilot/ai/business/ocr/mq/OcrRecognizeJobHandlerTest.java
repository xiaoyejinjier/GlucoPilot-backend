package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.ai.business.ocr.service.OcrTaskService;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobOutcome;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OCR 任务处理器")
class OcrRecognizeJobHandlerTest {

    private static final long USER_ID = 5L;

    private static final long TASK_ID = 77L;

    private OcrTaskMapper taskMapper;

    private OcrTaskService ocrTaskService;

    private OcrRecognizeJobHandler handler;

    @BeforeEach
    void setUp() {
        taskMapper = mock(OcrTaskMapper.class);
        ocrTaskService = mock(OcrTaskService.class);
        handler = new OcrRecognizeJobHandler(taskMapper, ocrTaskService);
    }

    private static JobEnvelope envelope(Long taskId) {
        return JobEnvelope.of(JobTypes.OCR_RECOGNIZE, taskId, null, USER_ID, null);
    }

    @Test
    @DisplayName("类型与路由：认领只查状态，避免重复投递白跑一次视觉调用")
    void claimChecksPendingStatus() {
        assertThat(handler.type()).isEqualTo(JobTypes.OCR_RECOGNIZE);

        when(taskMapper.countPendingTask(TASK_ID)).thenReturn(1);
        assertThat(handler.claim(envelope(TASK_ID))).isTrue();

        when(taskMapper.countPendingTask(TASK_ID)).thenReturn(0);
        assertThat(handler.claim(envelope(TASK_ID))).isFalse();
    }

    @Test
    @DisplayName("缺 refId 的坏消息直接放弃，不去查库")
    void claimRejectsEnvelopeWithoutRefId() {
        assertThat(handler.claim(envelope(null))).isFalse();
        verify(taskMapper, never()).countPendingTask(anyLong());
    }

    @Test
    @DisplayName("执行：转调 recognizeNow 并恒返回 SUCCESS（失败已落 FAILED，重投没有意义）")
    void handleDelegatesToService() {
        assertThat(handler.handle(envelope(TASK_ID))).isEqualTo(JobOutcome.SUCCESS);

        verify(ocrTaskService).recognizeNow(TASK_ID, USER_ID);
    }
}
