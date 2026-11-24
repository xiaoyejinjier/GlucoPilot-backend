package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobProperties;
import com.threadfit.glucopilot.common.mq.job.JobPublisher;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OCR 任务派发口")
class OcrTaskJobsTest {

    private static final long USER_ID = 6L;

    private static final long TASK_ID = 88L;

    private static JobProperties eventDriven(boolean enabled) {
        JobProperties properties = new JobProperties();
        properties.setEventDriven(enabled);
        return properties;
    }

    @Test
    @DisplayName("开启时投递信封：refId=任务、userId 带上，jobId 可读")
    void publishSendsEnvelope() {
        JobPublisher publisher = mock(JobPublisher.class);

        assertThat(new OcrTaskJobs(publisher, eventDriven(true)).publish(USER_ID, TASK_ID)).isTrue();

        ArgumentCaptor<JobEnvelope> captor = ArgumentCaptor.forClass(JobEnvelope.class);
        verify(publisher).publish(captor.capture());
        JobEnvelope sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(JobTypes.OCR_RECOGNIZE);
        assertThat(sent.refId()).isEqualTo(TASK_ID);
        assertThat(sent.userId()).isEqualTo(USER_ID);
        assertThat(sent.jobId()).isEqualTo("OCR_RECOGNIZE:" + TASK_ID);
    }

    @Test
    @DisplayName("队列关闭时返回 false：调用方要退回本地线程池")
    void publishReportsFalseWhenChannelDisabled() {
        JobPublisher publisher = mock(JobPublisher.class);

        assertThat(new OcrTaskJobs(publisher, eventDriven(false)).publish(USER_ID, TASK_ID)).isFalse();
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("投递抛异常时返回 false，不把异常抛回创建任务的主流程")
    void publishSwallowsFailure() {
        JobPublisher publisher = mock(JobPublisher.class);
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());

        assertThat(new OcrTaskJobs(publisher, eventDriven(true)).publish(USER_ID, TASK_ID)).isFalse();
    }

    @Test
    @DisplayName("没有 taskId 时不投递")
    void publishSkipsWithoutTaskId() {
        JobPublisher publisher = mock(JobPublisher.class);

        assertThat(new OcrTaskJobs(publisher, eventDriven(true)).publish(USER_ID, null)).isFalse();
        verify(publisher, never()).publish(any());
    }
}
