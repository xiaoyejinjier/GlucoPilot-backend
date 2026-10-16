package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具调用元数据登记（B3）。
 * <p>
 * 守两条：① 每条调用都要留下"谁、哪个会话、第几次、成没成、多久"；
 * ② <b>记账失败绝不影响工具</b>——写库抛异常时必须是静默的，否则本来能答的问题会因为一张审计表而失败。
 */
class ToolCallTraceRecorderTest {

    private final ChatToolCallMapper mapper = mock(ChatToolCallMapper.class);

    private final ToolCallTraceRecorder recorder = new ToolCallTraceRecorder(mapper);

    private static ToolContext context(Long sessionId, Long userMsgId, AtomicInteger counter) {
        Map<String, Object> values = new HashMap<>();
        values.put(AiToolContext.USER_ID, 17L);
        if (sessionId != null) {
            values.put(AiToolContext.SESSION_ID, sessionId);
        }
        if (userMsgId != null) {
            values.put(AiToolContext.USER_MSG_ID, userMsgId);
        }
        if (counter != null) {
            values.put(AiToolContext.CALL_COUNTER, counter);
        }
        return new ToolContext(values);
    }

    @Test
    void 记录工具名会话与耗时且序号在一轮内递增() {
        AtomicInteger counter = new AtomicInteger();
        ToolContext context = context(48L, 100L, counter);

        recorder.record(context, "query_glucose_range", true, 12, null);
        recorder.record(context, "lookup_food_nutrition", false, 7, "BizException");

        ArgumentCaptor<ChatToolCall> captor = ArgumentCaptor.forClass(ChatToolCall.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());

        ChatToolCall first = captor.getAllValues().get(0);
        assertEquals(48L, first.getSessionId());
        assertEquals(17L, first.getUserId());
        assertEquals(100L, first.getUserMsgId());
        assertEquals("chat-round:100", first.getRoundKey());
        assertEquals("query_glucose_range", first.getToolName());
        assertEquals(1, first.getCallIndex());
        assertEquals(1, first.getOk());
        assertEquals(12, first.getDurationMs());
        assertNull(first.getErrorType());

        ChatToolCall second = captor.getAllValues().get(1);
        assertEquals("lookup_food_nutrition", second.getToolName());
        assertEquals(2, second.getCallIndex(), "同一轮内序号必须递增");
        assertEquals(0, second.getOk());
        assertEquals("BizException", second.getErrorType(), "失败只记异常类名，不记消息正文");
    }

    @Test
    void 没有会话上下文时不记账也不报错() {
        // 单测 / live 测试直接调工具的场景：不该写库，更不该抛异常
        assertDoesNotThrow(() -> recorder.record(context(null, null, new AtomicInteger()), "tool", true, 3, null));

        verify(mapper, never()).insert(any(ChatToolCall.class));
    }

    @Test
    void 没有计数器时序号退回1() {
        recorder.record(context(48L, 100L, null), "tool", true, 3, null);

        ArgumentCaptor<ChatToolCall> captor = ArgumentCaptor.forClass(ChatToolCall.class);
        verify(mapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getCallIndex());
    }

    @Test
    void 写库失败不影响调用方() {
        when(mapper.insert(any(ChatToolCall.class))).thenThrow(new IllegalStateException("table missing"));

        assertDoesNotThrow(() -> recorder.record(context(48L, 100L, new AtomicInteger()), "tool", true, 3, null));
    }
}
