package com.threadfit.glucopilot.ai.tool;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具上下文的键与取值约定。
 * <p>
 * <b>为什么需要它</b>：所有可作工具的领域方法第一个参数都是 {@code userId}，
 * 而这个值<b>绝不能作为模型可见的参数</b>——否则模型可以被提示词注入诱导去查他人的数据。
 * 正确做法是服务端在构造请求时通过 {@code ChatClient.prompt().toolContext(...)} 注入，
 * 工具方法再从这里取。模型既看不到它，也伪造不了它。
 * <p>
 * <b>取不到时的处理</b>：那说明调用方漏了注入，是<b>代码缺陷</b>而不是用户输入问题，
 * 因此直接抛 {@link IllegalStateException}，而不是返回一句"参数错误"让模型去猜。
 * 这类错误必须在开发/测试阶段就暴露，不能等到线上某个用户的问题答错才发现。
 */
public final class AiToolContext {

    /** 当前登录用户 ID。与 {@code ChatCompletionService} 注入时使用的键必须一致。 */
    public static final String USER_ID = "userId";

    /** 当前会话 ID（V21 起注入）：工具调用元数据要记到哪个会话上。 */
    public static final String SESSION_ID = "sessionId";

    /** 本轮用户消息 ID（V21 起注入）：同轮的多次工具调用共享它。 */
    public static final String USER_MSG_ID = "userMsgId";

    /**
     * 本轮工具调用计数器（V21 起由调用方放入的可变 {@code AtomicInteger}）。
     * <p>
     * 放在 toolContext 这份"每轮一个"的 map 里而不是静态字段：不需要清理、天然按轮隔离、
     * 也不会在多实例/多用户之间串号。值用 AtomicInteger 是因为一轮里可能有并行的工具调用。
     */
    public static final String CALL_COUNTER = "toolCallCounter";

    private AiToolContext() {
    }

    /**
     * 取当前用户 ID。
     *
     * @throws IllegalStateException 调用方没有注入 {@code userId}
     */
    public static Long requireUserId(ToolContext toolContext) {
        Object value = raw(toolContext, USER_ID);
        if (value == null) {
            throw new IllegalStateException("工具调用缺少 " + USER_ID
                    + "：调用方必须通过 .toolContext(Map.of(AiToolContext.USER_ID, userId)) 注入当前用户");
        }
        return toLong(value, USER_ID);
    }

    /**
     * 取可选的长整型上下文值（缺失返回 null）。
     * <p>
     * 与 {@link #requireUserId} 的区别：{@code userId} 缺失是<b>代码缺陷</b>必须炸；
     * 而 {@code sessionId / userMsgId} 缺失只意味着"这次调用不需要记工具元数据"
     * （例如单测、live 测试直接调工具，或将来别的地方复用这些工具），不该让工具本身失败。
     */
    public static Long optionalLong(ToolContext toolContext, String key) {
        Object value = raw(toolContext, key);
        return value == null ? null : toLong(value, key);
    }

    private static Object raw(ToolContext toolContext, String key) {
        return (toolContext == null || toolContext.getContext() == null)
                ? null
                : toolContext.getContext().get(key);
    }

    private static Long toLong(Object value, String key) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " 不是合法数字：" + value, e);
        }
    }
}
