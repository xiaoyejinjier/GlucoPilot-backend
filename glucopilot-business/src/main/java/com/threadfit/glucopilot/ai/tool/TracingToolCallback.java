package com.threadfit.glucopilot.ai.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 给工具回调套一层"记账"（B3）：调用前后记下工具名、成功与否、耗时。
 * <p>
 * <b>只加观测，不改行为</b>：定义与元数据原样透传、入参出参一字不改、异常照原样抛出——
 * 装饰器一旦"顺手修一下"返回值或吞掉异常，就会让工具的契约与测试全部失真。
 * 记完账再抛出，是为了在失败路径上也留下痕迹（失败最需要可追溯）。
 */
@RequiredArgsConstructor
public class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private final ToolCallTraceRecorder recorder;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        // 必须透传：框架用元数据决定"是否把结果回喂/是否可直接返回"，改错会静默改变整条链路
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        // 框架在不带 ToolContext 的路径（部分第三方 executor）会走这里：没有上下文就不记账，
        // 但仍然要正常执行工具。
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long startNanos = System.nanoTime();
        try {
            String result = delegate.call(toolInput, toolContext);
            recorder.record(toolContext, name(), true, elapsedMs(startNanos), null);
            return result;
        } catch (RuntimeException e) {
            recorder.record(toolContext, name(), false, elapsedMs(startNanos), e.getClass().getSimpleName());
            throw e;
        }
    }

    private String name() {
        return delegate.getToolDefinition().name();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
