package com.threadfit.glucopilot.ai.memory.mq;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("记忆抽取补扫来源")
class MemoryExtractReconcileSourceTest {

    private final ChatSessionMapper mapper = mock(ChatSessionMapper.class);

    private final MemoryExtractReconcileSource source = new MemoryExtractReconcileSource(mapper);

    @Test
    @DisplayName("名称固定：日志与指标按它分组")
    void nameIsStable() {
        assertThat(source.name()).isEqualTo("memory-extract");
    }

    @Test
    @DisplayName("宽限阈值用数据库时钟，而不是进程时钟")
    void graceUsesDatabaseClock() {
        LocalDateTime dbNow = LocalDateTime.of(2026, 3, 4, 5, 6, 7);
        when(mapper.selectDbNow()).thenReturn(dbNow);
        when(mapper.selectSessionsNeedingMemory(any(), anyInt())).thenReturn(List.of());

        source.pending(50);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectSessionsNeedingMemory(captor.capture(), eq(50));
        assertThat(captor.getValue())
                .isEqualTo(dbNow.minusMinutes(MemoryExtractReconcileSource.GRACE_MINUTES));
    }

    @Test
    @DisplayName("列名大小写都能取到值（H2 会把别名转大写）")
    void readsColumnsInEitherCase() {
        when(mapper.selectDbNow()).thenReturn(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
        Map<String, Object> lower = new HashMap<>();
        lower.put("sessionId", 31L);
        lower.put("userId", 41L);
        Map<String, Object> upper = new HashMap<>();
        upper.put("SESSIONID", 32L);
        upper.put("USERID", 42L);
        when(mapper.selectSessionsNeedingMemory(any(), anyInt())).thenReturn(List.of(lower, upper));

        List<JobEnvelope> jobs = source.pending(10);

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).type()).isEqualTo(JobTypes.MEMORY_EXTRACT);
        assertThat(jobs.get(0).refId()).isEqualTo(31L);
        assertThat(jobs.get(0).userId()).isEqualTo(41L);
        assertThat(jobs.get(0).reason()).isEqualTo("reconcile");
        assertThat(jobs.get(1).refId()).isEqualTo(32L);
        assertThat(jobs.get(1).userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("读不到数据库时钟时本轮直接跳过，不用进程时钟猜")
    void skipsRoundWhenClockUnavailable() {
        when(mapper.selectDbNow()).thenReturn(null);

        assertThat(source.pending(10)).isEmpty();
        verify(mapper, org.mockito.Mockito.never()).selectSessionsNeedingMemory(any(), anyInt());
    }

    @Test
    @DisplayName("缺 sessionId 的行被跳过，不让坏数据变成任务")
    void skipsRowWithoutSessionId() {
        when(mapper.selectDbNow()).thenReturn(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
        Map<String, Object> broken = new HashMap<>();
        broken.put("userId", 41L);
        when(mapper.selectSessionsNeedingMemory(any(), anyInt())).thenReturn(List.of(broken));

        assertThat(source.pending(10)).isEmpty();
    }
}