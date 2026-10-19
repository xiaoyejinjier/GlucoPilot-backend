package com.threadfit.glucopilot.ai.memory.assembly;

/**
 * 一轮 prompt 的"分片元数据"（③ 期）：这一轮送给模型的内容由哪几块组成、各占多少 token。
 *
 * <h2>为什么只记元数据，不记内容</h2>
 * 表 {@code chat_context} 有 {@code content_text} / {@code content_json} 两列，可以存整包窗口 JSON。
 * 本实现<b>刻意一律留空</b>，理由与审计表同源：
 * <ul>
 *   <li><b>它会是用户健康内容的第三份副本</b>：原文在 {@code chat_message}，压缩摘要留在
 *       {@code chat_session.context_summary}，再把每轮 prompt 整包落一份，删除权就彻底失效了
 *       ——用户删掉对话之后，健康内容还以 prompt 快照的形式躺在另一张表里；</li>
 *   <li><b>写入量与读放大都不划算</b>：每轮每会话都写一遍完整上下文，表的增长是"对话量 × 窗口大小"，
 *       而它的真实用途只有"这一轮为什么这么贵/为什么模型记不住前面"。那是<u>组成</u>问题，不是<u>内容</u>问题；</li>
 *   <li><b>要定位内容有更好的路</b>：{@code trigger_msg_id} / {@code assistant_msg_id} 已经指向
 *       {@code chat_message} 的对应行，需要看原文时按 ID 回查即可（还能顺带尊重那边的逻辑删除）。</li>
 * </ul>
 *
 * <h2>字段对应关系</h2>
 * 一条分片落成 {@code chat_context} 的一行：
 * <ul>
 *   <li>{@code contextType} → {@code context_type}（1系统提示 3历史摘要 5工具结果 6其他）；</li>
 *   <li>{@code contextKey} → {@code context_key}（{@code system_prompt} / {@code long_term_memory} /
 *       {@code context_summary} / {@code tool_digest} / {@code history_messages}）；</li>
 *   <li>{@code tokenEstimate} → {@code token_estimate}（本分片的估算 token，由
 *       {@link TokenEstimator} 算出，与预算裁决用的是同一个数）；</li>
 *   <li>{@code sortNo} → {@code sort_no}（本轮内各分片的固定展示顺序，见各工厂方法）。</li>
 * </ul>
 * 五类分片的 token 之和不等于 {@code prompt_tokens}：本轮用户消息自身不单独成片，
 * 而"系统提示"这一片用的是固定开销（系统提示词 + 工具定义 + 消息骨架）。这是刻意的——
 * 分片回答的是"这一轮的钱花在哪几块上"，不承诺逐 token 对账。
 *
 * @param contextType  分片类型（与 DDL 的 {@code chk_cc_type} 取值一致）
 * @param contextKey   分片键（见各工厂方法）
 * @param tokenEstimate 本分片的估算 token（≥0）
 * @param sortNo       本轮内的展示顺序
 */
public record ChatContextShard(int contextType, String contextKey, int tokenEstimate, int sortNo) {

    /** DDL：1 系统提示。 */
    public static final int TYPE_SYSTEM_PROMPT = 1;
    /** DDL：3 历史摘要。 */
    public static final int TYPE_SUMMARY = 3;
    /** DDL：5 工具结果。 */
    public static final int TYPE_TOOL_RESULT = 5;
    /**
     * DDL：6 其他。
     * <p>
     * 长期记忆与历史消息都落这一类：DDL 的六种类型里没有为它们留位置，而它们确实既不是系统提示、
     * 也不是摘要/业务数据/工具结果。用 6 + 明确的 {@code context_key} 表达，比硬塞进"4 业务数据"
     * 更不容易误导后来的人（"业务数据"指的是血糖/饮食那类结构化数据）。
     */
    public static final int TYPE_OTHER = 6;

    /** sort_no 的五档顺序：越靠前的分片越"基础"。 */
    public static final int SORT_SYSTEM_PROMPT = 0;
    public static final int SORT_LONG_TERM_MEMORY = 1;
    public static final int SORT_CONTEXT_SUMMARY = 2;
    public static final int SORT_TOOL_DIGEST = 3;
    public static final int SORT_HISTORY_MESSAGES = 4;

    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    public static final String KEY_LONG_TERM_MEMORY = "long_term_memory";
    public static final String KEY_CONTEXT_SUMMARY = "context_summary";
    public static final String KEY_TOOL_DIGEST = "tool_digest";
    public static final String KEY_HISTORY_MESSAGES = "history_messages";

    /** 系统提示（含工具定义与消息骨架的固定开销）。 */
    public static ChatContextShard systemPrompt(int tokens) {
        return new ChatContextShard(TYPE_SYSTEM_PROMPT, KEY_SYSTEM_PROMPT, Math.max(tokens, 0), SORT_SYSTEM_PROMPT);
    }

    /** 跨会话长期记忆（③ 期新增的注入块）。 */
    public static ChatContextShard longTermMemory(int tokens) {
        return new ChatContextShard(TYPE_OTHER, KEY_LONG_TERM_MEMORY, Math.max(tokens, 0), SORT_LONG_TERM_MEMORY);
    }

    /** 滚动摘要。 */
    public static ChatContextShard summary(int tokens) {
        return new ChatContextShard(TYPE_SUMMARY, KEY_CONTEXT_SUMMARY, Math.max(tokens, 0), SORT_CONTEXT_SUMMARY);
    }

    /** 最近工具结果摘要。 */
    public static ChatContextShard toolDigest(int tokens) {
        return new ChatContextShard(TYPE_TOOL_RESULT, KEY_TOOL_DIGEST, Math.max(tokens, 0), SORT_TOOL_DIGEST);
    }

    /** 回放进 prompt 的历史消息。 */
    public static ChatContextShard historyMessages(int tokens) {
        return new ChatContextShard(TYPE_OTHER, KEY_HISTORY_MESSAGES, Math.max(tokens, 0), SORT_HISTORY_MESSAGES);
    }
}
