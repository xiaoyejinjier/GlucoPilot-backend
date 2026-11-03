package com.threadfit.glucopilot.ai.model.dto;

import java.util.List;

/**
 * AI 用量报表：把 {@code chat_message} 里已经落库的 token 数按「天 × 模型」汇总出来。
 *
 * <h2>为什么只有对话链路</h2>
 * 全项目只有助手消息落了 {@code prompt_tokens/completion_tokens}（见 {@code ChatCompletionServiceImpl.persist}）。
 * 摘要、记忆抽取、内容审核、饮食分析、OCR 都只取 {@code .content()}，没有记用量——所以这份报表
 * <b>不是全量成本</b>，是"可见的那部分成本"。{@link Report#note()} 会把这句话原样带给前端，
 * 避免运营看到数字就以为覆盖了全部调用。
 *
 * <h2>为什么维度是「天 × 模型」而不是「天 × 场景」</h2>
 * {@code chat_message} 上没有场景列：对话只有 CHAT 与 CHAT_IMAGE（后者的阶段 2 也走 CHAT 链路），
 * 按场景拆只会得到两个永远分不清的桶。模型维度才是能对上账的那一维（不同模型单价不同）。
 */
public final class AiUsageDtos {

    private AiUsageDtos() {
    }

    /**
     * 一行汇总。
     *
     * @param date             日期，{@code yyyy-MM-dd}（库里按天分组）
     * @param modelCode        模型编码；历史行可能为空，统一显示为 {@link #UNKNOWN_MODEL}
     * @param promptTokens     输入 token 合计
     * @param completionTokens 输出 token 合计
     * @param totalTokens      两者之和（前端直接展示，避免每处都自己加）
     * @param calls            调用次数（助手消息条数）
     */
    public record DailyUsage(String date,
                             String modelCode,
                             long promptTokens,
                             long completionTokens,
                             long totalTokens,
                             long calls) {
    }

    /** 区间合计，口径与 {@link DailyUsage} 一致。 */
    public record Totals(long promptTokens, long completionTokens, long totalTokens, long calls) {
    }

    /**
     * 报表本体。
     *
     * @param startDate 起始日期（含），{@code yyyy-MM-dd}
     * @param endDate   结束日期（含），{@code yyyy-MM-dd}
     * @param rows      按日期倒序、同日按模型编码升序
     * @param totals    区间合计
     * @param note      口径说明（只有对话链路有真实 token 记录）
     */
    public record Report(String startDate,
                         String endDate,
                         List<DailyUsage> rows,
                         Totals totals,
                         String note) {
    }

    /** 历史消息没有 model_code 时的占位。 */
    public static final String UNKNOWN_MODEL = "（未记录）";

    /** 口径说明，前后端共用一句话，避免各写一份解释。 */
    public static final String NOTE =
            "仅统计对话链路（chat_message 里已落库的 token）。摘要、记忆抽取、内容审核、饮食分析与 OCR "
                    + "目前不记录用量，因此这里的数字低于真实成本。";
}
