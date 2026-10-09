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

    private AiToolContext() {
    }

    /**
     * 取当前用户 ID。
     *
     * @throws IllegalStateException 调用方没有注入 {@code userId}
     */
    public static Long requireUserId(ToolContext toolContext) {
        Object value = (toolContext == null || toolContext.getContext() == null)
                ? null
                : toolContext.getContext().get(USER_ID);
        if (value == null) {
            throw new IllegalStateException("工具调用缺少 " + USER_ID
                    + "：调用方必须通过 .toolContext(Map.of(AiToolContext.USER_ID, userId)) 注入当前用户");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(USER_ID + " 不是合法数字：" + value, e);
        }
    }
}
