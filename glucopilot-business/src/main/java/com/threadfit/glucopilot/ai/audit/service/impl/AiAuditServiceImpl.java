package com.threadfit.glucopilot.ai.audit.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.ai.audit.service.AiAuditService;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import com.threadfit.glucopilot.ai.business.ocr.entity.OcrTask;
import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.ai.memory.entity.ChatUserMemoryEntity;
import com.threadfit.glucopilot.ai.memory.mapper.ChatUserMemoryMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 审计读模型实现。
 *
 * <h2>三个共同处理</h2>
 * <ul>
 *   <li><b>关键词归一</b>：去首尾空白、限 {@value #KEYWORD_MAX_CHARS} 字，超长抛 400 而不是截断——
 *       静默截断会让运营以为「搜到了全部」；</li>
 *   <li><b>分页对象每次新建</b>：MyBatis-Plus 的 {@link Page} 带状态，跨请求复用会串页；</li>
 *   <li><b>实体 → 审计行</b>在服务里完成，controller 只转发：契约稳定，将来换 SQL 不影响前端。</li>
 * </ul>
 *
 * <h2>为什么不加时间区间过滤</h2>
 * 本期四张表都按主键倒序 + 少量等值条件翻页，与既有的会话审计列表保持一致；
 * 加区间需要额外索引（{@code chat_tool_call} 有 {@code (session_id, gmt_create)}，其余表没有时间索引），
 * 等真的有「查某天发生了什么」的需求时再一起建索引，避免现在为猜测付出 DDL 成本。
 */
@Service
@RequiredArgsConstructor
public class AiAuditServiceImpl implements AiAuditService {

    /** 关键词上限：与 {@code AdminChatSessionServiceImpl} 保持一致。 */
    static final int KEYWORD_MAX_CHARS = 50;

    /** 多模态结果预览长度：JSON 可能几十 KB，列表只给够判断「识别成了什么」的长度。 */
    static final int RESULT_PREVIEW_CHARS = 200;

    private final ChatToolCallMapper toolCallMapper;
    private final ChatUserMemoryMapper memoryMapper;
    private final ChatSessionMapper sessionMapper;
    private final OcrTaskMapper ocrTaskMapper;

    @Override
    public PageResult<AiAuditDtos.ToolCallRow> toolCalls(Long userId, Long sessionId, String toolName,
                                                         Boolean ok, PageQuery query) {
        String normalizedTool = normalizeName(toolName);
        Integer okFlag = ok == null ? null : (ok ? 1 : 0);
        IPage<ChatToolCall> result = toolCallMapper.selectAuditPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                userId, sessionId, normalizedTool, okFlag);
        List<AiAuditDtos.ToolCallRow> rows = result.getRecords().stream()
                .map(call -> new AiAuditDtos.ToolCallRow(
                        value(call.getId()),
                        value(call.getSessionId()),
                        value(call.getUserId()),
                        call.getUserMsgId(),
                        call.getToolName(),
                        value(call.getCallIndex()),
                        call.getOk() != null && call.getOk() == 1,
                        call.getDurationMs(),
                        call.getErrorType(),
                        call.getResultDigest(),
                        call.getGmtCreate()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    @Override
    public PageResult<AiAuditDtos.MemoryRow> memories(Long userId, String memoryKey, String keyword,
                                                      boolean includeDeleted, PageQuery query) {
        String normalizedKey = normalizeName(memoryKey);
        String normalizedKeyword = normalizeKeyword(keyword);
        IPage<ChatUserMemoryEntity> result = memoryMapper.selectAuditPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                userId, normalizedKey, normalizedKeyword, includeDeleted);
        List<AiAuditDtos.MemoryRow> rows = result.getRecords().stream()
                .map(memory -> new AiAuditDtos.MemoryRow(
                        value(memory.getId()),
                        value(memory.getUserId()),
                        memory.getMemoryKey(),
                        memory.getMemoryText(),
                        memory.getSourceSessionId(),
                        value(memory.getConfidence()),
                        memory.getIsDeleted() != null && memory.getIsDeleted() == 1,
                        memory.getGmtCreate(),
                        memory.getGmtModified()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    @Override
    public PageResult<AiAuditDtos.SummaryRow> summaries(Long userId, String keyword, PageQuery query) {
        String normalizedKeyword = normalizeKeyword(keyword);
        IPage<ChatSession> result = sessionMapper.selectSummaryAuditPage(
                new Page<>(query.getPageNum(), query.getPageSize()), userId, normalizedKeyword);
        List<AiAuditDtos.SummaryRow> rows = result.getRecords().stream()
                .map(session -> new AiAuditDtos.SummaryRow(
                        value(session.getId()),
                        value(session.getUserId()),
                        session.getTitle(),
                        value(session.getMsgCount()),
                        session.getSummaryUptoMsgId(),
                        session.getContextSummary() == null ? 0 : session.getContextSummary().length(),
                        session.getContextSummary(),
                        session.getGmtModified()))
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    @Override
    public PageResult<AiAuditDtos.OcrTaskRow> ocrTasks(Long userId, Integer bizType, Integer taskStatus,
                                                       PageQuery query) {
        IPage<OcrTask> result = ocrTaskMapper.selectAuditPage(
                new Page<>(query.getPageNum(), query.getPageSize()), userId, bizType, taskStatus);
        List<AiAuditDtos.OcrTaskRow> rows = result.getRecords().stream()
                .map(task -> {
                    String json = task.getOcrResultJson();
                    return new AiAuditDtos.OcrTaskRow(
                            value(task.getId()),
                            value(task.getUserId()),
                            value(task.getMediaId()),
                            value(task.getBizType()),
                            value(task.getTaskStatus()),
                            durationMillis(task),
                            task.getErrorMsg(),
                            json == null ? 0 : json.length(),
                            preview(json),
                            task.getGmtCreate(),
                            task.getFinishedAt());
                })
                .toList();
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), rows);
    }

    /**
     * 耗时只能近似：{@code ocr_task} 没有独立的耗时列，用「完成时间 - 创建时间」代替。
     * 任务未结束（{@code finished_at} 为空）时返回 null，前端显示「进行中」而不是误导性的 0。
     */
    private static Long durationMillis(OcrTask task) {
        if (task.getFinishedAt() == null || task.getGmtCreate() == null) {
            return null;
        }
        return Duration.between(task.getGmtCreate(), task.getFinishedAt()).toMillis();
    }

    private static String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String collapsed = text.strip();
        return collapsed.length() <= RESULT_PREVIEW_CHARS
                ? collapsed
                : collapsed.substring(0, RESULT_PREVIEW_CHARS) + "…";
    }

    /** 精确匹配用的名字：空白视为「不过滤」，超长同样 400。 */
    private static String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() > KEYWORD_MAX_CHARS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "过滤条件最长 " + KEYWORD_MAX_CHARS + " 个字符，当前 " + trimmed.length() + " 个");
        }
        return trimmed;
    }

    private static String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > KEYWORD_MAX_CHARS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "关键词最长 " + KEYWORD_MAX_CHARS + " 个字符，当前 " + trimmed.length() + " 个");
        }
        return trimmed;
    }

    private static long value(Long number) {
        return number == null ? 0L : number;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
