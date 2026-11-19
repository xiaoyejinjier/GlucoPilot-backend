package com.threadfit.glucopilot.ai.model.job;

import com.threadfit.glucopilot.ai.model.mapper.AiCallLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账单保留期清理：分批删到删不动为止；保留期 &lt;= 0 视为关闭。
 */
class AiCallLogCleanupJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 3, 30);

    @Test
    void 应按批删除直到最后一批不满() {
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        // 第一批满批（5000）说明可能还有；第二批 120 条说明删完了
        when(mapper.deleteOlderThan(any(), anyInt())).thenReturn(5000, 120);
        AiCallLogCleanupJob job = new AiCallLogCleanupJob(mapper);
        ReflectionTestUtils.setField(job, "retentionDays", 90);
        ReflectionTestUtils.setField(job, "batchSize", 5000);

        int deleted = job.runOnce(NOW);

        assertThat(deleted).isEqualTo(5120);
        verify(mapper, times(2)).deleteOlderThan(NOW.minusDays(90), 5000);
    }

    @Test
    void 保留期为零时应关闭清理而不是删光() {
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        AiCallLogCleanupJob job = new AiCallLogCleanupJob(mapper);
        ReflectionTestUtils.setField(job, "retentionDays", 0);
        ReflectionTestUtils.setField(job, "batchSize", 5000);

        assertThat(job.runOnce(NOW)).isZero();
        verify(mapper, never()).deleteOlderThan(any(), anyInt());
    }

    @Test
    void 一批就删完时只查一次() {
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(mapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
        AiCallLogCleanupJob job = new AiCallLogCleanupJob(mapper);
        ReflectionTestUtils.setField(job, "retentionDays", 30);
        ReflectionTestUtils.setField(job, "batchSize", 1000);

        assertThat(job.runOnce(NOW)).isZero();
        verify(mapper, times(1)).deleteOlderThan(NOW.minusDays(30), 1000);
    }
}
