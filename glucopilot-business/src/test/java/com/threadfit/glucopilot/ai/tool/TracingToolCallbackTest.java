package com.threadfit.glucopilot.ai.tool;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记账装饰器：只加观测、不改行为。
 * <p>
 * 这一层最怕的是"顺手改一下"（改返回、吞异常、丢掉 ToolMetadata），
 * 因为它包在**每一个**模型可调用的工具外面，任何偏差都会静默影响整条工具链路的语义。
 */
class TracingToolCallbackTest {

    private final ToolCallTraceRecorder recorder = mock(ToolCallTraceRecorder.class);

    private static ToolCallback delegate(ToolMetadata metadata, String result) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("query_glucose_range");
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(metadata);
        when(callback.call(any(), any())).thenReturn(result);
        return callback;
    }

    @Test
    void 定义与元数据原样透传() {
        ToolMetadata metadata = ToolMetadata.builder().returnDirect(true).build();
        ToolCallback inner = delegate(metadata, "ok");
        TracingToolCallback traced = new TracingToolCallback(inner, recorder);

        assertSame(inner.getToolDefinition(), traced.getToolDefinition());
        assertSame(metadata, traced.getToolMetadata(), "ToolMetadata 必须原样透传，它决定结果是否回喂/直接返回");
    }

    @Test
    void 成功时记成功并按原样返回结果() {
        ToolCallback inner = delegate(ToolMetadata.builder().build(), "{\"avg\":6.8}");
        TracingToolCallback traced = new TracingToolCallback(inner, recorder);
        ToolContext context = new ToolContext(Map.of(AiToolContext.SESSION_ID, 48L));

        String result = traced.call("{}", context);

        assertEquals("{\"avg\":6.8}", result);
        verify(recorder).record(eq(context), eq("query_glucose_range"), eq(true), anyLong(), isNull());
    }

    @Test
    void 失败时记失败并把异常原样抛出() {
        ToolCallback inner = mock(ToolCallback.class);
        when(inner.getToolDefinition()).thenReturn(mock(ToolDefinition.class));
        when(inner.getToolDefinition().name()).thenReturn("query_glucose_range");
        when(inner.call(any(), any())).thenThrow(new IllegalStateException("工具调用缺少 userId"));
        TracingToolCallback traced = new TracingToolCallback(inner, recorder);
        ToolContext context = new ToolContext(Map.of(AiToolContext.SESSION_ID, 48L));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> traced.call("{}", context));

        assertEquals("工具调用缺少 userId", error.getMessage());
        verify(recorder).record(eq(context), eq("query_glucose_range"), eq(false), anyLong(),
                eq("IllegalStateException"));
    }

    @Test
    void 不带上下文的调用照常执行但不记账() {
        ToolCallback inner = delegate(ToolMetadata.builder().build(), "ok");
        when(inner.call(any())).thenReturn("ok");
        TracingToolCallback traced = new TracingToolCallback(inner, recorder);

        assertEquals("ok", traced.call("{}"));

        verify(recorder, never()).record(any(), any(), anyBoolean(), anyLong(), any());
    }
}
