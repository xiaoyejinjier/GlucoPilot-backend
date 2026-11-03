package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatContextEntity;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.service.impl.ChatContextQueryServiceImpl;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分片审计查询：分组、顺序、合计与两种"查不到"的区分。
 *
 * <p>重点守第三条：**会话不存在**（404）与**会话存在但还没记录分片**（空列表）必须分开。
 * 混成一个，排障的人就分不清"接口坏了"还是"这轮真的没记"。
 */
class ChatContextQueryServiceTest {

    private ChatContextMapper contextMapper;
    private ChatSessionMapper sessionMapper;
    private ChatContextQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        contextMapper = mock(ChatContextMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        service = new ChatContextQueryServiceImpl(contextMapper, sessionMapper);
        ChatSession session = new ChatSession();
        session.setId(9L);
        when(sessionMapper.selectById(9L)).thenReturn(session);
        when(sessionMapper.selectById(404L)).thenReturn(null);
    }

    @Test
    @DisplayName("按轮次分组，并在组内保持 sort_no 顺序")
    void groupsByRoundAndKeepsSortOrder() {
        when(contextMapper.selectList(any())).thenReturn(List.of(
                shard(1, 1, "system_prompt", 900, 0, 100L, null, "deepseek-v4-pro"),
                shard(1, 2, "long_term_memory", 120, 1, 100L, null, "deepseek-v4-pro"),
                shard(1, 3, "history_messages", 700, 4, 100L, null, "deepseek-v4-pro"),
                shard(2, 4, "system_prompt", 950, 0, 200L, 201L, "deepseek-v4-flash")));

        ChatContextDtos.ContextView view = service.view(9L, null);

        assertThat(view.rounds()).hasSize(2);
        assertThat(view.rounds().get(0).contextRound()).isEqualTo(1);
        assertThat(view.rounds().get(0).shards()).extracting(ChatContextDtos.Shard::contextKey)
                .containsExactly("system_prompt", "long_term_memory", "history_messages");
        assertThat(view.rounds().get(0).totalTokens()).isEqualTo(1720);
        assertThat(view.rounds().get(1).modelCode()).isEqualTo("deepseek-v4-flash");
        assertThat(view.rounds().get(1).assistantMsgId()).isEqualTo(201L);
        assertThat(view.totalTokens()).isEqualTo(2670);
    }

    @Test
    @DisplayName("会话存在但没有分片记录时返回空列表而不是 404")
    void existingSessionWithoutShardsIsEmpty() {
        when(contextMapper.selectList(any())).thenReturn(List.of());

        ChatContextDtos.ContextView view = service.view(9L, 1);

        assertThat(view.rounds()).isEmpty();
        assertThat(view.totalTokens()).isZero();
    }

    @Test
    @DisplayName("会话不存在或已删除应 404，且不查分片表")
    void missingSessionIsNotFound() {
        assertThatThrownBy(() -> service.view(404L, null))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("会话 ID 为空应是参数错误")
    void nullSessionIdIsBadRequest() {
        assertThatThrownBy(() -> service.view(null, null))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("token 为空的分片按 0 计，不抛 NPE")
    void nullTokenEstimateCountsAsZero() {
        ChatContextEntity row = shard(1, 5, "tool_digest", null, 3, 100L, null, "deepseek-v4-pro");
        when(contextMapper.selectList(any())).thenReturn(List.of(row));

        ChatContextDtos.ContextView view = service.view(9L, null);

        assertThat(view.rounds()).hasSize(1);
        assertThat(view.rounds().get(0).totalTokens()).isZero();
    }

    private static ChatContextEntity shard(int round, int sortNo, String key, Integer tokens,
                                           Integer type, Long triggerMsgId, Long assistantMsgId, String model) {
        ChatContextEntity row = new ChatContextEntity();
        row.setSessionId(9L);
        row.setContextRound(round);
        row.setSortNo(sortNo);
        row.setContextKey(key);
        row.setTokenEstimate(tokens);
        row.setContextType(type);
        row.setTriggerMsgId(triggerMsgId);
        row.setAssistantMsgId(assistantMsgId);
        row.setModelCode(model);
        return row;
    }
}
