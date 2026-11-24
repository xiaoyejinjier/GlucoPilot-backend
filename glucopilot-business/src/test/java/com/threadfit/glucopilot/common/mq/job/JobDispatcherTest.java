package com.threadfit.glucopilot.common.mq.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务分流：认领/处理两段与异常收敛。
 */
class JobDispatcherTest {

    private static final JobEnvelope ARTICLE = JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 11L, 1, 2L, "publish");

    @Test
    void 没有对应处理器时按永久失败处理不做重试() {
        JobDispatcher dispatcher = new JobDispatcher(List.of());
        assertThat(dispatcher.dispatch(ARTICLE)).isEqualTo(JobOutcome.PERMANENT);
        assertThat(dispatcher.registeredTypes()).isEmpty();
    }

    @Test
    void 未知类型按永久失败处理() {
        JobDispatcher dispatcher = new JobDispatcher(List.of());
        JobEnvelope unknown = new JobEnvelope("x:1", "WHATEVER", 1L, null, null, null, 0, "now");
        assertThat(dispatcher.dispatch(unknown)).isEqualTo(JobOutcome.PERMANENT);
    }

    @Test
    void 认领失败说明已被别人办过应直接确认() {
        JobHandler handler = handler(JobTypes.MODERATION_ARTICLE, false, JobOutcome.SUCCESS);
        JobDispatcher dispatcher = new JobDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(ARTICLE)).isEqualTo(JobOutcome.SUCCESS);
        // 认领不到就不该再调模型：这是"重复投递不重复扣费"的关键
        verify(handler, never()).handle(any());
    }

    @Test
    void 处理成功返回成功() {
        JobHandler handler = handler(JobTypes.MODERATION_ARTICLE, true, JobOutcome.SUCCESS);
        JobDispatcher dispatcher = new JobDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(ARTICLE)).isEqualTo(JobOutcome.SUCCESS);
        verify(handler).handle(ARTICLE);
    }

    @Test
    void 处理器返回永久失败应原样透出() {
        JobHandler handler = handler(JobTypes.MODERATION_ARTICLE, true, JobOutcome.PERMANENT);
        JobDispatcher dispatcher = new JobDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(ARTICLE)).isEqualTo(JobOutcome.PERMANENT);
    }

    @Test
    void 处理抛异常应收敛成可重试() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.type()).thenReturn(JobTypes.MODERATION_ARTICLE);
        when(handler.claim(any())).thenReturn(true);
        when(handler.handle(any())).thenThrow(new IllegalStateException("upstream 502"));
        JobDispatcher dispatcher = new JobDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(ARTICLE)).isEqualTo(JobOutcome.RETRYABLE);
    }

    @Test
    void 认领抛异常也应可重试() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.type()).thenReturn(JobTypes.MODERATION_ARTICLE);
        when(handler.claim(any())).thenThrow(new IllegalStateException("db down"));
        JobDispatcher dispatcher = new JobDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(ARTICLE)).isEqualTo(JobOutcome.RETRYABLE);
        verify(handler, never()).handle(any());
    }

    @Test
    void 空信封按永久失败处理() {
        assertThat(new JobDispatcher(List.of()).dispatch(null)).isEqualTo(JobOutcome.PERMANENT);
    }

    private static JobHandler handler(String type, boolean claimed, JobOutcome outcome) {
        JobHandler handler = mock(JobHandler.class);
        when(handler.type()).thenReturn(type);
        when(handler.claim(any())).thenReturn(claimed);
        when(handler.handle(any())).thenReturn(outcome);
        return handler;
    }
}
