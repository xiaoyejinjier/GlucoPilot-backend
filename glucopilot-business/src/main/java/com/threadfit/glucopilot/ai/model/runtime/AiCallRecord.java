package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.support.AiCallContext;

/**
 * 一次模型调用的账单快照（写入 {@code ai_call_log} 的唯一原料）。
 *
 * <p>为什么是 record 而不是直接塞 {@code ChatResponse}：调用方拿到的是"框架对象"，
 * 里面既有正文也有工具调用，还可能含用户内容；账单只要这几个数字。做成窄类型之后，
 * 「不许往账单里写正文」这条约束由类型本身保证，而不是靠代码评审记得。
 *
 * @param context       归属（场景/用户/会话）；为 null 表示调用发生在 {@code AiCallContext} 之外
 * @param channel       text / vision
 * @param modelCode     模型身份（不可改的编码）
 * @param modelEndpoint 实际发给厂商的模型名
 * @param usage         用量；失败或没拿到时为 null（账单里对应列写 NULL，不写 0）
 * @param latencyMs     耗时毫秒
 * @param stream        是否流式
 * @param ok            是否成功
 * @param errorCode     失败时的异常类名；成功为 null
 */
public record AiCallRecord(AiCallContext.Call context,
                           String channel,
                           String modelCode,
                           String modelEndpoint,
                           ModelCallUsage usage,
                           long latencyMs,
                           boolean stream,
                           boolean ok,
                           String errorCode) {

    /** 没有归属信息时也要能记账（框架内部的探活、启动期校验等）。 */
    public static AiCallRecord of(AiCallContext.Call context, String channel, String modelCode, String modelEndpoint,
                                  ModelCallUsage usage, long latencyMs, boolean stream, boolean ok, String errorCode) {
        return new AiCallRecord(context, channel, modelCode, modelEndpoint, usage, latencyMs, stream, ok, errorCode);
    }
}
