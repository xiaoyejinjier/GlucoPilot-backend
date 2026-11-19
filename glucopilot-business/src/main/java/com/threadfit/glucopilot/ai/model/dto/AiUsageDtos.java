package com.threadfit.glucopilot.ai.model.dto;

import java.util.List;

/**
 * AI 用量报表：从 {@code ai_call_log}（V75 账本）按「天 × 场景 × 模型」汇总。
 *
 * <h2>为什么口径从 chat_message 换到账本</h2>
 * 旧口径只统计 {@code chat_message.msg_role = 2} 的行，也就是「对话链路里落库的助手消息」。
 * 摘要、记忆抽取、内容审核、翻译、图片识别全都取完 {@code .content()} 就丢弃，从来不入账——
 * 于是报表会让人以为「AI 成本 = 对话成本」。账本写在 {@code DynamicChatModel.call/stream}
 * 这唯一漏斗上，覆盖每一个场景，这才是账单。
 *
 * <h2>为什么消息上的 prompt_tokens 还要留着</h2>
 * 它是<b>消息属性</b>：回答某条消息花了多少；账本是<b>调用流水</b>：一次工具回环会有多次模型调用。
 * 一个按轮、一个按调用，<b>不能相加</b>，页面必须写清楚。
 *
 * <h2>缓存命中率怎么算</h2>
 * {@code hit / (hit + miss)}，两者来自上游上报的同一份 usage，分母天然等于"有上报的 prompt token"。
 * 从未上报过命中的老行两者都是 0，此时命中率返回 {@code null} 而不是 0——
 * 「不知道」和「一次都没命中」在优化决策上是相反的结论。
 */
public final class AiUsageDtos {

    private AiUsageDtos() {
    }

    /**
     * 一行汇总。
     *
     * @param date             日期 {@code yyyy-MM-dd}
     * @param sceneCode        调用归属的场景（{@code SceneCode.name()}），空串表示框架内部/未归属
     * @param sceneLabel       场景中文名（未知编码回显编码本身）
     * @param modelCode        模型编码；历史行可能为空，显示为 {@link #UNKNOWN_MODEL}
     * @param promptTokens     输入 token 合计
     * @param completionTokens 输出 token 合计
     * @param cacheHitTokens   缓存命中 token 合计
     * @param cacheMissTokens  缓存未命中 token 合计
     * @param cacheHitRate     命中率（0~1，保留 4 位；无上报时为 {@code null}）
     * @param totalTokens      prompt + completion
     * @param calls            调用次数
     * @param failedCalls      其中失败的次数（{@code ok = 0}）
     */
    public record DailyUsage(String date,
                             String sceneCode,
                             String sceneLabel,
                             String modelCode,
                             long promptTokens,
                             long completionTokens,
                             long cacheHitTokens,
                             long cacheMissTokens,
                             Double cacheHitRate,
                             long totalTokens,
                             long calls,
                             long failedCalls) {
    }

    /** 区间合计，口径与 {@link DailyUsage} 一致。 */
    public record Totals(long promptTokens,
                         long completionTokens,
                         long cacheHitTokens,
                         long cacheMissTokens,
                         Double cacheHitRate,
                         long totalTokens,
                         long calls,
                         long failedCalls) {
    }

    /**
     * 报表本体。
     *
     * @param startDate 起始日期（含），{@code yyyy-MM-dd}
     * @param endDate   结束日期（含）
     * @param rows      按日期倒序、同日按场景升序、再按模型编码升序
     * @param totals    区间合计
     * @param note      口径说明
     */
    public record Report(String startDate,
                         String endDate,
                         List<DailyUsage> rows,
                         Totals totals,
                         String note) {
    }

    /** 历史行没有 model_code 时的占位。 */
    public static final String UNKNOWN_MODEL = "（未记录）";

    /** 没压到调用上下文里的行（框架内部调用、启动期探活）的场景编码占位。 */
    public static final String UNKNOWN_SCENE_CODE = "";

    /** 未归属场景的中文名。 */
    public static final String UNKNOWN_SCENE = "（未归属）";

    /** 口径说明，前后端共用一句话，避免各写一份解释。 */
    public static final String NOTE =
            "口径为模型调用流水（ai_call_log）：一个场景调几次算几次，包含失败的调用。"
                    + "这里的 token 与「消息上的 prompt_tokens」一个是按调用、一个是按轮次，两者不可相加。";
}
