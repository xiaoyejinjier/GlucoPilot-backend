package com.threadfit.glucopilot.ai.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * {@link RepeatAwareToolCallback} 的装饰器契约。
 *
 * <p>三条必须守住的边界：**命中时只执行一次**、**没有会话上下文时绝不去重**（否则不同用户会被串起来）、
 * **异常不被缓存**（把一次失败记住 30 分钟，用户会以为系统坏了）。
 */
class RepeatAwareToolCallbackTest {

    private static final long SESSION = 21L;

    private static final ToolContext WITH_SESSION =
            new ToolContext(Map.of(AiToolContext.USER_ID, 3L, AiToolContext.SESSION_ID, SESSION));

    private static ToolCallback delegate(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    void 同会话重复调用应只执行一次() {
        ToolCallback delegate = delegate("query_glucose_stats");
        when(delegate.call(anyString(), any())).thenReturn("{\"avg\":6.8}");
        RepeatAwareToolCallback callback = new RepeatAwareToolCallback(delegate, new ToolRepeatGuard());

        String first = callback.call("{\"days\":30}", WITH_SESSION);
        String second = callback.call("{\"days\":30}", WITH_SESSION);

        assertThat(first).isEqualTo("{\"avg\":6.8}");
        assertThat(second).isEqualTo(first);
        verify(delegate, times(1)).call("{\"days\":30}", WITH_SESSION);
    }

    @Test
    void 入参不同应各自执行() {
        ToolCallback delegate = delegate("query_glucose_stats");
        when(delegate.call(anyString(), any())).thenReturn("{}");
        RepeatAwareToolCallback callback = new RepeatAwareToolCallback(delegate, new ToolRepeatGuard());

        callback.call("{\"days\":30}", WITH_SESSION);
        callback.call("{\"days\":90}", WITH_SESSION);

        verify(delegate, times(2)).call(anyString(), any());
    }

    @Test
    void 空白差异不算不同入参() {
        ToolCallback delegate = delegate("query_glucose_stats");
        when(delegate.call(anyString(), any())).thenReturn("{}");
        RepeatAwareToolCallback callback = new RepeatAwareToolCallback(delegate, new ToolRepeatGuard());

        callback.call("{ \"days\": 30 }", WITH_SESSION);
        callback.call("{\n  \"days\":   30\n}", WITH_SESSION);

        verify(delegate, times(1)).call(anyString(), any());
    }

    @Test
    void 没有会话上下文时应直接透传不去重() {
        ToolCallback delegate = delegate("list_reminders");
        when(delegate.call(anyString(), any())).thenReturn("{}");
        RepeatAwareToolCallback callback = new RepeatAwareToolCallback(delegate, new ToolRepeatGuard());
        ToolContext withoutSession = new ToolContext(Map.of(AiToolContext.USER_ID, 3L));

        callback.call("{}", withoutSession);
        callback.call("{}", withoutSession);

        verify(delegate, times(2)).call(any(), any());
    }

    @Test
    void 首次就失败时不应把异常也缓存下来() {
        ToolCallback delegate = delegate("search_community_articles");
        when(delegate.call(anyString(), any()))
                .thenThrow(new IllegalStateException("数据库连接失败"))
                .thenReturn("{\"itemCount\":0}");
        RepeatAwareToolCallback callback = new RepeatAwareToolCallback(delegate, new ToolRepeatGuard());

        assertThatThrownBy(() -> callback.call("{\"keyword\":\"血糖\"}", WITH_SESSION))
                .isInstanceOf(IllegalStateException.class);
        // 第二次必须重新执行：缓存住一次失败，会让用户在这半小时里反复看到同一个错误
        assertThat(callback.call("{\"keyword\":\"血糖\"}", WITH_SESSION)).isEqualTo("{\"itemCount\":0}");
        verify(delegate, times(2)).call(anyString(), any());
    }

    @Test
    void 定义与元数据应原样透传() {
        ToolCallback delegate = delegate("get_session_state");
        ToolMetadata metadata = mock(ToolMetadata.class);
        when(delegate.getToolMetadata()).thenReturn(metadata);

        RepeatAwareToolCallback callback = new RepeatAwareToolCallback(delegate, new ToolRepeatGuard());

        assertThat(callback.getToolDefinition()).isSameAs(delegate.getToolDefinition());
        assertThat(callback.getToolMetadata()).isSameAs(metadata);
    }
}
