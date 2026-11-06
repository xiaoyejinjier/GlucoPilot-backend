package com.threadfit.glucopilot.ai.audit.dto;

import java.time.LocalDateTime;

/**
 * AI 审计六张列表的响应契约。
 *
 * <h2>为什么把六个 record 放在同一个文件里</h2>
 * 它们同属一个页面的六张表、共享同一种「行 = 一条流水」的形状，字段都在 10 个上下；
 * 拆成六个文件只会让调用方多六次 import，并不会让谁更清楚。
 *
 * <h2>为什么后两个（审核命中 / 翻译）也在这里，而不是在各自模块</h2>
 * 这两个接口对外是「AI 审计」的一部分（路径 {@code /admin/v1/ai/audit/**}、权限
 * {@code ai:chat:audit}），所以<b>契约由 ai 拥有</b>；但 SQL 仍在表的主人那里执行——
 * {@code community} / {@code localization} 各自实现 {@code ai.audit.service} 里的
 * {@code ModerationHitAuditQuery} / {@code TranslationAuditQuery} 端口，依赖方向仍是
 * 「业务 → ai」，{@code AiModuleBoundaryTest} 不被破例。
 *
 * <h2>哪些列刻意不返回</h2>
 * <ul>
 *   <li>工具调用：<b>不返回</b> {@code result_digest} 之外的工具返回内容——表里本来也只存 ≤512 字摘要，
 *       真正的返回内容没有落库（V21 的设计）；</li>
 *   <li>多模态：{@code ocr_result_json} 可能几十 KB，列表只给 {@code resultChars} 与 200 字预览；</li>
 *   <li>审核命中：只有位置与片段哈希，<b>没有原文</b>（V43 的隐私设计）；</li>
 *   <li>翻译：只给 200 字译文预览与字符数；</li>
 *   <li>全部六张表都<b>不返回</b>逻辑删除审计列与版本号：审计页不展示、也不能据此做任何操作。</li>
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

    /**
     * 内容审核命中明细（{@code community_moderation_hit}，V43）。
     *
     * <p>一条命中 = 模型在某个文章的某个版本 / 某条评论里指出了一处违规点。
     *
     * @param revision    文章版本号；评论没有版本，为 null
     * @param severity    1 低 / 2 中 / 3 高（DDL 上 {@code chk_cmh_severity} 限定 1~3）
     * @param snippetHash 命中片段的 sha256——<b>表里刻意不存原文</b>，所以这里也没有
     * @param reason      模型给出的说明（提示词要求不摘抄原文）
     */
    public record ModerationHitRow(long id,
                                   String targetType,
                                   long targetId,
                                   Integer revision,
                                   String categoryCode,
                                   int severity,
                                   Integer confidence,
                                   Integer startOffset,
                                   Integer endOffset,
                                   Integer snippetLen,
                                   String snippetHash,
                                   String reason,
                                   String modelCode,
                                   String promptVer,
                                   LocalDateTime createdAt) {
    }

    /**
     * 翻译缓存（{@code content_localization}，V39 建 / V54 加 {@code prompt_ver}）。
     *
     * @param sourceHash        原文指纹（= sha256(提示词版本 + 原文 JSON)）；同一 resourceId
     *                          出现多行说明原文变过或换过提示词版本
     * @param translatedChars   译文 JSON 的字符数；为 0 说明还在 PENDING 或翻译失败
     * @param translatedPreview 译文前 200 字（去首尾空白后截断，超长补 {@code …}）
     */
    public record TranslationRow(String kind,
                                 String resourceId,
                                 String sourceHash,
                                 String locale,
                                 String status,
                                 String promptVer,
                                 int translatedChars,
                                 String translatedPreview,
                                 LocalDateTime updatedAt) {
    }
}
