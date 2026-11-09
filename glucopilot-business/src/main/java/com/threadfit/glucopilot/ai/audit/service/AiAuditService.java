package com.threadfit.glucopilot.ai.audit.service;

import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;

/**
 * AI 审计读模型（只读）。
 *
 * <h2>抽象理由</h2>
 * 四个查询各自源自不同的 mapper（chat / memory / ocr），但它们的<b>用法完全一致</b>：
 * 一页流水 + 少量过滤条件 + 倒序。把它们收在一个接口后面，controller 就只剩「转发参数」，
 * 将来换数据源（比如把工具调用改成写独立的审计表）也只动实现。
 *
 * <h2>共同的参数约定</h2>
 * <ul>
 *   <li>{@code userId} 为空表示不过滤用户；</li>
 *   <li>所有字符串过滤都<b>精确匹配</b>（{@code toolName}、{@code memoryKey}），
 *       只有 {@code keyword} 是模糊匹配且限长 50 字（超长直接 400，避免把 LIKE 打到大表全扫）；</li>
 *   <li>分页 {@code pageSize} 上限 100（{@link PageQuery} 上的 {@code @Max}），倒序按主键，
 *       保证翻页时新写入的数据不会把已看过的行挤到另一页。</li>
 * </ul>
 */
public interface AiAuditService {

    /** 工具调用流水。{@code ok} 为 null 表示成功失败都要。 */
    PageResult<AiAuditDtos.ToolCallRow> toolCalls(Long userId, Long sessionId, String toolName,
                                                  Boolean ok, PageQuery query);

    /** 长期记忆。{@code includeDeleted} 为 true 时连已逻辑删除的记录一起看（审计要能回答「曾经记过什么」）。 */
    PageResult<AiAuditDtos.MemoryRow> memories(Long userId, String memoryKey, String keyword,
                                               boolean includeDeleted, PageQuery query);

    /** 滚动摘要（只列有摘要的会话）。 */
    PageResult<AiAuditDtos.SummaryRow> summaries(Long userId, String keyword, PageQuery query);

    /** 多模态识别任务。 */
    PageResult<AiAuditDtos.OcrTaskRow> ocrTasks(Long userId, Integer bizType, Integer taskStatus,
                                                PageQuery query);
}
