package com.threadfit.glucopilot.ai.memory.mq;

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
import static org.mockito.Mockito.when;

@DisplayName("记忆抽取派发口")
class MemoryExtractJobsTest {

    private static final long USER_ID = 9L;

    private static final long SESSION_ID = 21L;

    @Test
    @DisplayName("开启时投递信封：类型、refId=会话、userId 与 reason 都要带上")
    void publishSendsEnvelope() {
        JobPublisher publisher = mock(JobPublisher.class);
        JobProperties properties = new JobProperties();
        properties.setEventDriven(true);

        assertThat(new MemoryExtractJobs(publisher, properties).publish(USER_ID, SESSION_ID, "session-ended"))
                .isTrue();

        ArgumentCaptor<JobEnvelope> captor = ArgumentCaptor.forClass(JobEnvelope.class);
        verify(publisher).publish(captor.capture());
        JobEnvelope sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(JobTypes.MEMORY_EXTRACT);
        assertThat(sent.refId()).isEqualTo(SESSION_ID);
        assertThat(sent.userId()).isEqualTo(USER_ID);
        assertThat(sent.reason()).isEqualTo("session-ended");
        assertThat(sent.jobId()).isEqualTo("MEMORY_EXTRACT:" + SESSION_ID);
    }

    @Test
    @DisplayName("队列关闭时返回 false：调用方要退回本地线程，别把记忆悄悄丢掉")
    void publishReportsFalseWhenChannelDisabled() {
        JobPublisher publisher = mock(JobPublisher.class);
        JobProperties properties = new JobProperties();
        properties.setEventDriven(false);

        assertThat(new MemoryExtractJobs(publisher, properties).publish(USER_ID, SESSION_ID, "session-ended"))
                .isFalse();
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("投递抛异常时也返回 false，而不是把异常抛回主流程")
    void publishSwallowsFailure() {
        JobPublisher publisher = mock(JobPublisher.class);
        JobProperties properties = new JobProperties();
        properties.setEventDriven(true);
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());

        assertThat(new MemoryExtractJobs(publisher, properties).publish(USER_ID, SESSION_ID, "explicit"))
                .isFalse();
    }

    @Test
    @DisplayName("没有 sessionId 时不投递")
    void publishSkipsWithoutSessionId() {
        JobPublisher publisher = mock(JobPublisher.class);
        JobProperties properties = new JobProperties();
        properties.setEventDriven(true);

        assertThat(new MemoryExtractJobs(publisher, properties).publish(USER_ID, null, "explicit")).isFalse();
        verify(publisher, never()).publish(any());
    }
}