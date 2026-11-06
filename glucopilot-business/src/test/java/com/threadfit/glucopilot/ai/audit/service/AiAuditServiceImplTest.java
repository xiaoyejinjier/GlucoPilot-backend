package com.threadfit.glucopilot.ai.audit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.ai.audit.dto.AiAuditDtos;
import com.threadfit.glucopilot.ai.audit.service.impl.AiAuditServiceImpl;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 审计读模型的投影规则。
 *
 * <p>这里验三件事：分页参数原样透传、null 列不炸（历史数据列可能为空）、
 * 以及两处「算出来」的字段（多模态耗时、结果预览截断）。SQL 形状与索引效果靠 E2E 在真实库上验。
 */
class AiAuditServiceImplTest {

    private ChatToolCallMapper toolCallMapper;
    private ChatUserMemoryMapper memoryMapper;
    private ChatSessionMapper sessionMapper;
    private OcrTaskMapper ocrTaskMapper;
    private AiAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        toolCallMapper = mock(ChatToolCallMapper.class);
        memoryMapper = mock(ChatUserMemoryMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        ocrTaskMapper = mock(OcrTaskMapper.class);
        service = new AiAuditServiceImpl(toolCallMapper, memoryMapper, sessionMapper, ocrTaskMapper);
    }

    @Test
    @DisplayName("工具调用：分页参数透传，ok 折成布尔，空列不炸")
    void toolCallsProjectAndPassPaging() {
        ChatToolCall call = new ChatToolCall();
        call.setId(7L);
        call.setSessionId(11L);
        call.setUserId(3L);
        call.setUserMsgId(99L);
        call.setToolName("query_glucose_range");
        call.setCallIndex(0);
        call.setOk(1);
        call.setDurationMs(42);
        call.setResultDigest("近 7 天空腹 6.1~7.4");
        call.setGmtCreate(LocalDateTime.of(2026, 11, 6, 10, 0));
        Page<ChatToolCall> page = new Page<>(2, 10);
        page.setRecords(List.of(call));
        page.setTotal(11);
        when(toolCallMapper.selectAuditPage(any(), eq(3L), isNull(), eq("query_glucose_range"), eq(1)))
                .thenReturn(page);

        PageQuery query = new PageQuery();
        query.setPageNum(2);
        query.setPageSize(10);
        PageResult<AiAuditDtos.ToolCallRow> result = service.toolCalls(3L, null, " query_glucose_range ", true, query);

        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getTotal()).isEqualTo(11);
        AiAuditDtos.ToolCallRow row = result.getRecords().get(0);
        assertThat(row.id()).isEqualTo(7L);
        assertThat(row.ok()).isTrue();
        assertThat(row.toolName()).isEqualTo("query_glucose_range");
        assertThat(row.resultDigest()).isEqualTo("近 7 天空腹 6.1~7.4");
        verify(toolCallMapper).selectAuditPage(any(), eq(3L), isNull(), eq("query_glucose_range"), eq(1));
    }

    @Test
    @DisplayName("工具调用：ok 为空表示成功失败都要，null 的 ok 列按失败展示")
    void toolCallsToleratesNullOk() {
        ChatToolCall call = new ChatToolCall();
        call.setId(1L);
        call.setOk(null);
        Page<ChatToolCall> page = new Page<>(1, 20);
        page.setRecords(List.of(call));
        when(toolCallMapper.selectAuditPage(any(), any(), any(), any(), isNull())).thenReturn(page);

        PageResult<AiAuditDtos.ToolCallRow> result =
                service.toolCalls(null, null, null, null, new PageQuery());

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).ok()).isFalse();
        assertThat(result.getRecords().get(0).sessionId()).isZero();
        assertThat(result.getRecords().get(0).durationMs()).isNull();
    }

    @Test
    @DisplayName("过滤条件超长直接 400，而不是静默截断")
    void oversizedFilterIsRejected() {
        String tooLong = "x".repeat(51);
        PageQuery query = new PageQuery();

        assertThatThrownBy(() -> service.toolCalls(null, null, tooLong, null, query))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("最长 50 个字符");
        assertThatThrownBy(() -> service.memories(null, null, tooLong, false, query))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("记忆：includeDeleted 透传，已删除行标出来")
    void memoriesPassIncludeDeletedAndMarkDeleted() {
        ChatUserMemoryEntity memory = new ChatUserMemoryEntity();
        memory.setId(5L);
        memory.setUserId(3L);
        memory.setMemoryKey("diet_taboo");
        memory.setMemoryText("不吃香菜");
        memory.setSourceSessionId(11L);
        memory.setConfidence(80);
        memory.setIsDeleted(1);
        Page<ChatUserMemoryEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(memory));
        when(memoryMapper.selectAuditPage(any(), eq(3L), eq("diet_taboo"), eq("香菜"), eq(true)))
                .thenReturn(page);

        PageResult<AiAuditDtos.MemoryRow> result = service.memories(3L, "diet_taboo", "香菜", true, new PageQuery());

        AiAuditDtos.MemoryRow row = result.getRecords().get(0);
        assertThat(row.deleted()).isTrue();
        assertThat(row.memoryText()).isEqualTo("不吃香菜");
        assertThat(row.confidence()).isEqualTo(80);
        assertThat(row.sourceSessionId()).isEqualTo(11L);
        verify(memoryMapper).selectAuditPage(any(), eq(3L), eq("diet_taboo"), eq("香菜"), anyBoolean());
    }

    @Test
    @DisplayName("摘要：字数按正文实际长度算，摘要为 null 时归零而不是 NPE")
    void summariesCountChars() {
        ChatSession withSummary = new ChatSession();
        withSummary.setId(21L);
        withSummary.setUserId(3L);
        withSummary.setTitle("血糖咨询");
        withSummary.setMsgCount(12);
        withSummary.setSummaryUptoMsgId(9L);
        withSummary.setContextSummary("用户主诉空腹偏高…");
        ChatSession withoutSummary = new ChatSession();
        withoutSummary.setId(22L);
        Page<ChatSession> page = new Page<>(1, 20);
        page.setRecords(List.of(withSummary, withoutSummary));
        when(sessionMapper.selectSummaryAuditPage(any(), isNull(), isNull())).thenReturn(page);

        PageResult<AiAuditDtos.SummaryRow> result = service.summaries(null, null, new PageQuery());

        assertThat(result.getRecords().get(0).summaryChars())
                .isEqualTo(withSummary.getContextSummary().length());
        assertThat(result.getRecords().get(0).summaryUptoMsgId()).isEqualTo(9L);
        assertThat(result.getRecords().get(1).summaryChars()).isZero();
        assertThat(result.getRecords().get(1).summary()).isNull();
    }

    @Test
    @DisplayName("多模态：耗时由完成时间减创建时间算，未完成时为 null，结果只给 200 字预览")
    void ocrTasksComputeDurationAndPreview() {
        OcrTask done = new OcrTask();
        done.setId(31L);
        done.setUserId(3L);
        done.setMediaId(9L);
        done.setBizType(2);
        done.setTaskStatus(2);
        done.setOcrResultJson("{\"foods\":[\"米饭\"]}".repeat(30));
        done.setGmtCreate(LocalDateTime.of(2026, 11, 6, 10, 0, 0));
        done.setFinishedAt(LocalDateTime.of(2026, 11, 6, 10, 0, 2, 500_000_000));
        OcrTask running = new OcrTask();
        running.setId(32L);
        running.setTaskStatus(1);
        running.setGmtCreate(LocalDateTime.of(2026, 11, 6, 10, 5));
        Page<OcrTask> page = new Page<>(1, 20);
        page.setRecords(List.of(done, running));
        when(ocrTaskMapper.selectAuditPage(any(), eq(3L), eq(2), isNull())).thenReturn(page);

        PageResult<AiAuditDtos.OcrTaskRow> result = service.ocrTasks(3L, 2, null, new PageQuery());

        AiAuditDtos.OcrTaskRow first = result.getRecords().get(0);
        assertThat(first.durationMs()).isEqualTo(2500L);
        assertThat(first.resultChars()).isEqualTo("{\"foods\":[\"米饭\"]}".repeat(30).length());
        assertThat(first.resultPreview()).endsWith("…");
        assertThat(first.resultPreview()).hasSize(201);
        assertThat(result.getRecords().get(1).durationMs()).isNull();
        assertThat(result.getRecords().get(1).resultPreview()).isNull();
    }
}
