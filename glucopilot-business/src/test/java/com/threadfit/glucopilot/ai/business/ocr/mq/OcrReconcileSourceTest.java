package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OCR 补扫来源")
class OcrReconcileSourceTest {

    private final OcrTaskMapper mapper = mock(OcrTaskMapper.class);

    private final OcrReconcileSource source = new OcrReconcileSource(mapper);

    @Test
    @DisplayName("名称固定：日志与指标按它分组")
    void nameIsStable() {
        assertThat(source.name()).isEqualTo("ocr-recognize");
    }

    @Test
    @DisplayName("先回收卡在进行中的任务，再列待识别的任务")
    void reclaimsStaleThenListsPending() {
        LocalDateTime dbNow = LocalDateTime.of(2026, 5, 6, 7, 8, 9);
        when(mapper.selectDbNow()).thenReturn(dbNow);
        when(mapper.selectPendingTasks(any(), anyInt())).thenReturn(List.of());

        source.pending(20);

        ArgumentCaptor<LocalDateTime> stale = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).requeueStaleRunning(stale.capture(), eq(OcrReconcileSource.RETRY_MARKER));
        verify(mapper).failStaleRunning(eq(stale.getValue()), eq(OcrReconcileSource.RETRY_MARKER),
                eq(OcrReconcileSource.FAIL_MESSAGE));
        assertThat(stale.getValue())
                .isEqualTo(dbNow.minusMinutes(OcrReconcileSource.STALE_MINUTES));

        ArgumentCaptor<LocalDateTime> grace = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectPendingTasks(grace.capture(), eq(20));
        assertThat(grace.getValue())
                .isEqualTo(dbNow.minusMinutes(OcrReconcileSource.GRACE_MINUTES));
    }

    @Test
    @DisplayName("列名大小写都能取到值（H2 会把别名转大写）")
    void readsColumnsInEitherCase() {
        when(mapper.selectDbNow()).thenReturn(LocalDateTime.of(2026, 5, 6, 7, 8, 9));
        Map<String, Object> lower = new HashMap<>();
        lower.put("taskId", 11L);
        lower.put("userId", 12L);
        Map<String, Object> upper = new HashMap<>();
        upper.put("TASKID", 13L);
        upper.put("USERID", 14L);
        when(mapper.selectPendingTasks(any(), anyInt())).thenReturn(List.of(lower, upper));

        List<JobEnvelope> jobs = source.pending(10);

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).type()).isEqualTo(JobTypes.OCR_RECOGNIZE);
        assertThat(jobs.get(0).refId()).isEqualTo(11L);
        assertThat(jobs.get(0).userId()).isEqualTo(12L);
        assertThat(jobs.get(1).refId()).isEqualTo(13L);
        assertThat(jobs.get(1).userId()).isEqualTo(14L);
        assertThat(jobs.get(0).reason()).isEqualTo("reconcile");
    }

    @Test
    @DisplayName("读不到数据库时钟时整轮跳过：不用进程时钟猜，也别误判正在跑的任务")
    void skipsRoundWhenClockUnavailable() {
        when(mapper.selectDbNow()).thenReturn(null);

        assertThat(source.pending(10)).isEmpty();
        verify(mapper, never()).requeueStaleRunning(any(), any());
        verify(mapper, never()).selectPendingTasks(any(), anyInt());
    }

    @Test
    @DisplayName("缺 taskId 的行被跳过，不让坏数据变成任务")
    void skipsRowWithoutTaskId() {
        when(mapper.selectDbNow()).thenReturn(LocalDateTime.of(2026, 5, 6, 7, 8, 9));
        Map<String, Object> broken = new HashMap<>();
        broken.put("userId", 12L);
        when(mapper.selectPendingTasks(any(), anyInt())).thenReturn(List.of(broken));

        assertThat(source.pending(10)).isEmpty();
    }
}
