package com.threadfit.glucopilot.community.moderation.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobProperties;
import com.threadfit.glucopilot.common.mq.job.JobPublisher;
import com.threadfit.glucopilot.common.mq.job.JobTypes;
import com.threadfit.glucopilot.common.exception.BizException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 审核任务派发口的行为：开关语义与"派发失败不影响主流程"。
 *
 * <p>派发是在用户请求的事务里调用的——抛异常就等于用户发帖失败，而那时内容其实已经写进库了。 所以这里守两件事：{@code event-driven=false}
 * 时必须完全不碰 MQ（否则轮询与消息双开，同一条被审两次）；投递异常必须被吞掉（正确性由补扫兜底）。
 */
class CommunityModerationJobsTest {

    private final JobPublisher publisher = mock(JobPublisher.class);

    private final JobProperties properties = new JobProperties();

    private final CommunityModerationJobs jobs = new CommunityModerationJobs(publisher, properties);

    @Test
    void 关闭消息通道时不派发() {
        properties.setEventDriven(false);

        jobs.articleSubmitted(12L, 2);

        verify(publisher, never()).publish(any());
    }

    @Test
    void 文章派发带上版本号() {
        ArgumentCaptor<JobEnvelope> captor = ArgumentCaptor.forClass(JobEnvelope.class);

        jobs.articleSubmitted(12L, 2);

        verify(publisher).publish(captor.capture());
        JobEnvelope envelope = captor.getValue();
        assertEquals(JobTypes.MODERATION_ARTICLE, envelope.type());
        assertEquals(12L, envelope.refId());
        assertEquals(2, envelope.revision());
    }

    @Test
    void 评论派发携带作者() {
        ArgumentCaptor<JobEnvelope> captor = ArgumentCaptor.forClass(JobEnvelope.class);

        jobs.commentCreated(33L, 99L);

        verify(publisher).publish(captor.capture());
        JobEnvelope envelope = captor.getValue();
        assertEquals(JobTypes.MODERATION_COMMENT, envelope.type());
        assertEquals(33L, envelope.refId());
        assertEquals(99L, envelope.userId());
    }

    @Test
    void 投递失败不应影响主流程() {
        when(publisher.publish(any()))
                .thenThrow(new BizException(com.threadfit.glucopilot.common.api.ErrorCode.SERVICE_UNAVAILABLE, "MQ 不可用"));

        jobs.articleSubmitted(12L, 2);
    }
}
