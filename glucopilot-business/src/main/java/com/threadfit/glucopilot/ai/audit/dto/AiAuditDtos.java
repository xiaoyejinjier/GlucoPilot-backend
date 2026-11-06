package com.threadfit.glucopilot.ai.audit.dto;

import java.time.LocalDateTime;

/**
 * AI 审计四张列表的响应契约。
 *
 * <h2>为什么把四个 record 放在同一个文件里</h2>
 * 它们同属一个页面的四张表、共享同一种「行 = 一条流水」的形状，字段都在 10 个上下；
 * 拆成四个文件只会让调用方多四次 import，并不会让谁更清楚。
 *
 * <h2>哪些列刻意不返回</h2>
 * <ul>
 *   <li>工具调用：<b>不返回</b> {@code result_digest} 之外的工具返回内容——表里本来也只存 ≤512 字摘要，
 *       真正的返回内容没有落库（V21 的设计）；</li>
 *   <li>多模态：{@code ocr_result_json} 可能几十 KB，列表只给 {@code resultChars} 与 200 字预览；</li>
 *   <li>全部四张表都<b>不返回</b>逻辑删除审计列与版本号：审计页不展示、也不能据此做任何操作。</li>
 * </ul>
 */
public final class AiAuditDtos {

    private AiAuditDtos() {
    }

    /**
     * 工具调用流水（{@code chat_tool_call}）。
     *
     * @param ok     1 成功 / 0 失败；失败原因只有 {@code errorType}（异常类名），没有堆栈
     */
    public record ToolCallRow(long id,
                              long sessionId,
                              long userId,
                              Long userMsgId,
                              String toolName,
                              int callIndex,
                              boolean ok,
                              Integer durationMs,
                              String errorType,
                              String resultDigest,
                              LocalDateTime createdAt) {
    }

    /**
     * 长期记忆（{@code chat_user_memory}）。
     *
     * <p>{@code memoryText} 给全文而不是预览：单条已被四道闸限制在 200 字以内，
     * 而且「系统到底记了什么」正是这一页要回答的问题，截断会让它答不出来。
     *
     * @param includeDeleted 语义上由查询参数决定，不体现在行里；行里 {@code deleted} 只表示这行是否已逻辑删除
     */
    public record MemoryRow(long id,
                            long userId,
                            String memoryKey,
                            String memoryText,
                            Long sourceSessionId,
                            int confidence,
                            boolean deleted,
                            LocalDateTime createdAt,
                            LocalDateTime updatedAt) {
    }

    /**
     * 滚动摘要（{@code chat_session.context_summary} 派生，不是独立表）。
     *
     * <p>{@code summary} 给全文：它已经被 {@code ai.memory.summary-max-chars} 限长（默认 800 字），
     * 而审计恰恰要判断「摘要有没有丢掉关键信息」，截断就看不出。
     *
     * @param summary                      摘要正文；本行能存在说明它非空
     * @param summaryUptoMsgId             摘要已覆盖到哪条消息（指针只前进），为空表示还没有摘要
     * @param summaryUpdatedAt             摘要所在会话行的修改时间（摘要更新会带上 gmt_modified）
     * @param pendingMessagesSinceSummary  摘要之后又积累了多少条消息（= 会话消息数 - 指针之后的消息数），
     *                                     为 null 表示这一行没算（本期不提供该字段的查询，保留扩展位）
     */
    public record SummaryRow(long sessionId,
                             long userId,
                             String title,
                             int msgCount,
                             Long summaryUptoMsgId,
                             int summaryChars,
                             String summary,
                             LocalDateTime summaryUpdatedAt) {
    }

    /**
     * 多模态识别任务（{@code ocr_task}）。
     *
     * <p>表里<b>没有</b> model_code / token / 耗时的独立列：{@code durationMs} 是
     * {@code finished_at - gmt_create} 的近似值（为空说明任务还没结束），模型名只可能藏在
     * {@code ocr_result_json} 里，所以不给。
     *
     * @param bizType       1 血糖 / 2 餐食
     * @param taskStatus    0 待处理 / 1 进行中 / 2 成功 / 3 失败
     * @param resultPreview 成功时取 {@code ocr_result_json} 前 200 字，失败时为 null
     */
    public record OcrTaskRow(long id,
                             long userId,
                             long mediaId,
                             int bizType,
                             int taskStatus,
                             Long durationMs,
                             String errorMsg,
                             int resultChars,
                             String resultPreview,
                             LocalDateTime createdAt,
                             LocalDateTime finishedAt) {
    }
}
