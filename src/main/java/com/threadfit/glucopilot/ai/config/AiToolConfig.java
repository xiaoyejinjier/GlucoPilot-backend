package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.tool.GlucoseQueryTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具（Function Calling）的注册装配。
 * <p>
 * <b>每个工具类注册成一个 {@link ToolCallbackProvider} Bean</b>，由 {@code AiToolRegistry}
 * 收集后再按 {@code ai.tools.allow-list} 过滤。刻意不用"把带 {@code @Tool} 的对象直接丢给
 * ChatClient"的写法，原因有二：
 * <ol>
 *   <li><b>白名单是第一道闸</b>：{@code @Tool} 对象一旦被直接交给 client，就等于绕过了白名单——
 *       以后任何人往工具类里加一个方法，它都会立刻对模型可见，且 diff 里看不出来；</li>
 *   <li><b>显式登记便于审计</b>：想知道"模型到底能调用哪些工具"，看这个类 + yaml 白名单即可，
 *       不需要扫全项目的注解。</li>
 * </ol>
 * <p>
 * 新增工具类的步骤：写带 {@code @Tool} 的组件 → 在这里加一个 {@code ToolCallbackProvider} Bean
 * → 在 {@code ai.tools.allow-list} 登记方法名（必须与 {@code @Tool(name=...)} 完全一致）。
 */
@Configuration(proxyBeanMethods = false)
public class AiToolConfig {

    @Bean
    public ToolCallbackProvider glucoseQueryToolProvider(GlucoseQueryTools glucoseQueryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(glucoseQueryTools)
                .build();
    }
}
