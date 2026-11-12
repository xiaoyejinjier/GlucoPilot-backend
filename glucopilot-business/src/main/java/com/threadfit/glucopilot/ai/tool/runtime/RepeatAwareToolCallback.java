package com.threadfit.glucopilot.ai.tool.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 给工具回调套一层「同会话同入参去重」（见 {@link ToolRepeatGuard}）。
 *
 * <p><b>只拦重复，不改语义</b>：定义与元数据原样透传；命中缓存时返回的正是上次那串 JSON，
 * 模型看到的东西与真正执行一次完全相同，因此它不需要知道自己被拦了。
 * 入参不同就照原样执行——那不是重复，是新的问题。
 *
 * <p><b>没有会话上下文就透传</b>：{@code ToolContext} 里没有 {@code SESSION_ID} 时（例如非对话场景、
 * 或第三方 executor 走的是不带上下文的 {@code call(String)}），拿不到「同一次会话」这个前提，
 * 强行按工具名去重会把不同用户的查询串在一起——那比重复调用危险得多。
 */
@Slf4j
@RequiredArgsConstructor
public class RepeatAwareToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private final ToolRepeatGuard guard;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        // 必须透传：元数据决定"结果是否回喂模型"，改错会静默改变整条链路
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        // 无上下文 → 无会话标识 → 不做去重，正常执行
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long sessionId = AiToolContext.optionalLong(toolContext, AiToolContext.SESSION_ID);
        if (sessionId == null) {
            return delegate.call(toolInput, toolContext);
        }

        ToolRepeatGuard.Key key =
                new ToolRepeatGuard.Key(sessionId, name(), normalize(toolInput));

        var cached = guard.recall(key);
        if (cached.isPresent()) {
            // 记一笔 debug 就够：工具记账装饰器仍会记录这次调用，
            // 「到底命中过多少次」看那份记录即可，不必再打一条 info 噪音。
            log.debug("工具重复调用已复用上次结果 sessionId={} tool={}", sessionId, key.toolName());
            return cached.get();
        }

        String result = delegate.call(toolInput, toolContext);
        guard.remember(key, result);
        return result;
    }

    private String name() {
        return delegate.getToolDefinition().name();
    }

    /** 入参规范化：空白差异不该让同一份参数被当成两次不同调用。 */
    private static String normalize(String toolInput) {
        if (toolInput == null) {
            return "";
        }
        return toolInput.replaceAll("\\s+", " ").trim();
    }
}
