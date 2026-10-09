package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.config.AIProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具白名单的过滤语义。
 * <p>
 * 白名单是防「提示词注入诱导模型调用未授权工具」的最后一道闸，所以这里钉住三件事：
 * 空白名单必须一个都不放、登记了才放、名字对不上要告警但不影响启动。
 */
class AiToolRegistryTest {

    @Test
    void 白名单为空时一个工具都不应放出() {
        AiToolRegistry registry = registry(allowList(), provider("a", "b"));

        assertThat(registry.callbacks()).isEmpty();
    }

    @Test
    void 白名单只放出登记过的工具() {
        AiToolRegistry registry = registry(allowList("a"), provider("a", "b", "c"));

        assertThat(registry.callbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("a");
    }

    @Test
    void 多个工具类的工具应被汇总后一起过滤() {
        AiToolRegistry registry = registry(allowList("b", "c"), provider("a"), provider("b", "c"));

        assertThat(registry.callbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void 白名单名字对不上时不应抛错() {
        // 名字写错只告警、不阻断启动：否则一个拼写错误会让整个应用起不来，
        // 而正确做法是让它照常可用、只是模型暂时没有工具（日志里有明确线索）
        AiToolRegistry registry = registry(allowList("not_exists"), provider("a"));

        assertThat(registry.callbacks()).isEmpty();
    }

    // ---------- 构造辅助 ----------

    private static List<String> allowList(String... names) {
        return Arrays.asList(names);
    }

    private static AiToolRegistry registry(List<String> allowList, ToolCallbackProvider... providers) {
        AIProperties properties = new AIProperties();
        properties.getTools().setAllowList(allowList);
        return new AiToolRegistry(properties, objectProvider(providers), objectProvider());
    }

    private static ToolCallbackProvider provider(String... names) {
        ToolCallback[] callbacks = Arrays.stream(names)
                .map(AiToolRegistryTest::callbackNamed)
                .toArray(ToolCallback[]::new);
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    private static ToolCallback callbackNamed(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static <T> ObjectProvider<T> objectProvider(T... beans) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(Stream.of(beans));
        return provider;
    }
}
