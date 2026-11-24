package com.threadfit.glucopilot.common.mq.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补扫：按域聚合、单域失败不影响其它域、开关生效。
 */
class JobReconcilerTest {

    private final JobProperties properties = new JobProperties();
    private final JobPublisher publisher = mock(JobPublisher.class);

    @Test
    void 应把各域待办任务全部投递() {
        JobEnvelope first = JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 1L, 1, null, "publish");
        JobEnvelope second = JobEnvelope.of(JobTypes.OCR_RECOGNIZE, 2L, null, 9L, "upload");
        JobReconciler reconciler = new JobReconciler(properties, publisher, List.of(
                source("moderation", List.of(first)),
                source("ocr", List.of(second))));

        assertThat(reconciler.reconcileOnce()).isEqualTo(2);
        verify(publisher).publish(first);
        verify(publisher).publish(second);
    }

    @Test
    void 没有待办时不投递() {
        JobReconciler reconciler = new JobReconciler(properties, publisher, List.of(source("moderation", List.of())));

        assertThat(reconciler.reconcileOnce()).isZero();
        verify(publisher, never()).publish(any());
    }

    @Test
    void 关闭补扫时不查库() {
        properties.setReconcileEnabled(false);
        JobReconcileSource source = mock(JobReconcileSource.class);
        when(source.name()).thenReturn("moderation");
        JobReconciler reconciler = new JobReconciler(properties, publisher, List.of(source));

        assertThat(reconciler.reconcileOnce()).isZero();
        verify(source, never()).pending(anyInt());
    }

    @Test
    void 单个域查询失败不影响其它域() {
        JobReconcileSource broken = mock(JobReconcileSource.class);
        when(broken.name()).thenReturn("ocr");
        doThrow(new IllegalStateException("db down")).when(broken).pending(anyInt());
        JobEnvelope envelope = JobEnvelope.of(JobTypes.MEMORY_EXTRACT, 5L, null, 7L, "session-ended");
        JobReconciler reconciler = new JobReconciler(properties, publisher, List.of(
                broken, source("memory", List.of(envelope))));

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        verify(publisher, times(1)).publish(envelope);
    }

    @Test
    void 投递失败应终止本轮避免把队列打爆() {
        JobEnvelope first = JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 1L, 1, null, "publish");
        JobEnvelope second = JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 2L, 1, null, "publish");
        doThrow(new IllegalStateException("queue disabled")).when(publisher).publish(any());
        JobReconciler reconciler = new JobReconciler(properties, publisher, List.of(
                source("moderation", List.of(first, second))));

        assertThat(reconciler.reconcileOnce()).isZero();
        verify(publisher, times(1)).publish(any());
    }

    private static JobReconcileSource source(String name, List<JobEnvelope> pending) {
        JobReconcileSource source = mock(JobReconcileSource.class);
        when(source.name()).thenReturn(name);
        when(source.pending(anyInt())).thenReturn(pending);
        return source;
    }
}
