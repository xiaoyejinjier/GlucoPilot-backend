package com.threadfit.glucopilot.ai.memory.mq;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobOutcome;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("记忆抽取任务处理器")
class MemoryExtractJobHandlerTest {

    private static final long USER_ID = 7L;

    private static final long SESSION_ID = 11L;

    private ChatSessionMapper sessionMapper;

    private UserChatMemoryService memoryService;

    private MemoryExtractJobHandler handler;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        memoryService = mock(UserChatMemoryService.class);
        handler = new MemoryExtractJobHandler(sessionMapper, memoryService);
    }

    private static JobEnvelope envelope(String reason) {
        return JobEnvelope.of(JobTypes.MEMORY_EXTRACT, SESSION_ID, null, USER_ID, reason);
    }

    @Test
    @DisplayName("类型与路由：认领要回库确认会话还欠一次抽取")
    void claimAsksDatabaseWhetherSessionNeedsExtraction() {
        assertThat(handler.type()).isEqualTo(JobTypes.MEMORY_EXTRACT);

        when(sessionMapper.countSessionNeedingMemory(SESSION_ID)).thenReturn(1);
        assertThat(handler.claim(envelope("session-ended"))).isTrue();

        when(sessionMapper.countSessionNeedingMemory(SESSION_ID)).thenReturn(0);
        assertThat(handler.claim(envelope("session-ended"))).isFalse();
    }

    @Test
    @DisplayName("缺 refId 的坏消息直接放弃，不去查库")
    void claimRejectsEnvelopeWithoutRefId() {
        JobEnvelope broken = JobEnvelope.of(JobTypes.MEMORY_EXTRACT, null, null, USER_ID, null);

        assertThat(handler.claim(broken)).isFalse();
        verify(sessionMapper, never()).countSessionNeedingMemory(anyLong());
    }

    @Test
    @DisplayName("执行：透传 userId 与 reason，结果恒为 SUCCESS（记忆是增强项，重投没有意义）")
    void handleRunsExtractionAndAlwaysSucceeds() {
        when(memoryService.extractNow(USER_ID, SESSION_ID, "session-archived")).thenReturn(2);

        assertThat(handler.handle(envelope("session-archived"))).isEqualTo(JobOutcome.SUCCESS);
        verify(memoryService).extractNow(USER_ID, SESSION_ID, "session-archived");
    }

    @Test
    @DisplayName("执行：reason 为空时退化成 session-ended，日志里能看出是谁触发的")
    void handleFallsBackToDefaultReason() {
        when(memoryService.extractNow(anyLong(), anyLong(), anyString())).thenReturn(0);

        assertThat(handler.handle(envelope(" "))).isEqualTo(JobOutcome.SUCCESS);
        verify(memoryService).extractNow(USER_ID, SESSION_ID, MemoryExtractJobHandler.DEFAULT_REASON);
    }
}