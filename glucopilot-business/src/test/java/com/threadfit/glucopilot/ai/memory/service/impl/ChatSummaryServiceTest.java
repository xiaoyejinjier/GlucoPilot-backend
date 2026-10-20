package com.threadfit.glucopilot.ai.memory.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.threadfit.glucopilot.ai.memory.service.ChatSummaryService;

/**
 * 滚动摘要（② 期）。
 * <p>
 * 摘要是个"锦上添花但不能出错"的任务，规则要钉死：
 * <ol>
 *   <li><b>只压窗口外的部分</b>（保留最近 N 条原文不动），且只压"还没被覆盖"的（{@code summary_upto_msg_id} 之后）；</li>
 *   <li><b>写回只前进</b>：慢任务不会把指针拉回去（否则同一段原文被反复压缩，越压越糊）；</li>
 *   <li><b>失败不写半截</b>：模型报错/返回空/写库失败都只记日志，窗口里仍有原文；</li>
 *   <li><b>单飞</b>：同一会话同时只跑一个任务；线程池拒绝也不能影响本轮回复。</li>
 * </ol>
 */
class ChatSummaryServiceTest {

    private static final Long USER_ID = 17L;
    private static final Long SESSION_ID = 48L;

    private final ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final AiPromptTemplateService promptTemplateService = mock(AiPromptTemplateService.class);

    private final AIProperties properties = new AIProperties();

    /** 摘要请求的 options（关思维链/输出封顶）由它给出；本测试只关心被调用。 */
    private final com.threadfit.glucopilot.ai.core.SceneModelResolver sceneModelResolver =
            mock(com.threadfit.glucopilot.ai.core.SceneModelResolver.class);

    /** 直接在当前线程执行，便于断言"异步任务真的做了什么"。 */
    private final Executor directExecutor = Runnable::run;

    private ChatClient chatClient;

    private ChatClient.ChatClientRequestSpec requestSpec;

    private ChatClient.CallResponseSpec callSpec;

    private ChatSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        // 用 thenAnswer 而不是 thenReturn：返回类型是 ChatOptions.Builder<?>，泛型推断在 thenReturn 上过不去
        when(sceneModelResolver.summaryOptionsBuilder())
                .thenAnswer(invocation -> org.springframework.ai.deepseek.DeepSeekChatOptions.builder());
        when(requestSpec.options(any(org.springframework.ai.chat.prompt.ChatOptions.Builder.class)))
                .thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(promptTemplateService.systemPrompt(SceneCode.SUMMARY)).thenReturn("摘要提示词");

        service = new ChatSummaryServiceImpl(chatClient, chatSessionMapper, chatMessageMapper,
                promptTemplateService, sceneModelResolver, properties, new ChatRoundGuard(), directExecutor);
    }

    private static ChatMessage message(long id, int role, String text) {
        ChatMessage row = new ChatMessage();
        row.setId(id);
        row.setSessionId(SESSION_ID);
        row.setMsgRole(role);
        row.setMsgType(1);
        row.setContentText(text);
        return row;
    }

    /** 造 12 条消息（倒序返回，与 selectRecent 一致）。 */
    private void stubMessages() {
        List<ChatMessage> rows = new ArrayList<>();
        for (int i = 12; i >= 1; i--) {
            rows.add(message(i, i % 2 == 0 ? CodeEnum.ChatMsgRole.ASSISTANT.getCode()
                    : CodeEnum.ChatMsgRole.USER.getCode(), "第 " + i + " 条消息"));
        }
        when(chatMessageMapper.selectRecent(eq(SESSION_ID), anyInt(), anyInt())).thenReturn(rows);
    }

    @Test
    void 只压缩窗口外的消息并推进指针() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectContextSummary(SESSION_ID)).thenReturn(null);
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenReturn("用户问过血糖趋势；助手建议记录餐后血糖");
        when(chatSessionMapper.updateSummary(eq(SESSION_ID), anyString(), anyLong())).thenReturn(1);

        assertTrue(service.runSummarize(USER_ID, SESSION_ID));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> upto = ArgumentCaptor.forClass(Long.class);
        verify(chatSessionMapper).updateSummary(eq(SESSION_ID), summary.capture(), upto.capture());
        assertEquals(6L, upto.getValue(), "12 条里保留最近 6 条，摘要应覆盖到第 6 条（即窗口外的最后一条）");
        assertTrue(summary.getValue().contains("建议记录餐后血糖"));

        // 送进模型的输入应当只包含窗口外的原文（第 1..6 条），不含最近 6 条
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(input.capture());
        assertTrue(input.getValue().contains("第 1 条消息"));
        assertTrue(input.getValue().contains("第 6 条消息"));
        assertFalse(input.getValue().contains("第 7 条消息"), "窗口内的原文不该被压缩：" + input.getValue());
    }

    @Test
    void 已覆盖过的消息不再重复压缩() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectContextSummary(SESSION_ID)).thenReturn("已有摘要");
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(6L);
        when(callSpec.content()).thenReturn("新摘要");

        service.runSummarize(USER_ID, SESSION_ID);

        // 候选只剩 1..6，全部已覆盖 → 没有可压缩内容，不调模型、不写库
        verify(chatSessionMapper, never()).updateSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    void 写库被指针条件拒绝时不覆盖已有摘要() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenReturn("新摘要");
        when(chatSessionMapper.updateSummary(eq(SESSION_ID), anyString(), anyLong())).thenReturn(0);

        assertFalse(service.runSummarize(USER_ID, SESSION_ID), "指针已前进时本次结果丢弃");
    }

    @Test
    void 模型返回空时不写库() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenReturn("   ");

        assertFalse(service.runSummarize(USER_ID, SESSION_ID));

        verify(chatSessionMapper, never()).updateSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    void 模型抛异常时不写库也不抛出() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenThrow(new IllegalStateException("upstream 500"));

        assertFalse(service.runSummarize(USER_ID, SESSION_ID), "失败要降级为 false，不能把异常抛给调用方");

        verify(chatSessionMapper, never()).updateSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    void 消息还没超出窗口时不触发压缩() {
        properties.getMemory().setKeepRecentMessages(6);
        List<ChatMessage> rows = List.of(message(3, 1, "a"), message(2, 2, "b"), message(1, 1, "c"));
        when(chatMessageMapper.selectRecent(eq(SESSION_ID), anyInt(), anyInt())).thenReturn(rows);

        assertFalse(service.runSummarize(USER_ID, SESSION_ID));

        verify(chatClient, never()).prompt();
    }

    @Test
    void 摘要在上限内被截断() {
        properties.getMemory().setKeepRecentMessages(6);
        properties.getMemory().setSummaryMaxChars(120);
        stubMessages();
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenReturn("字".repeat(500));
        when(chatSessionMapper.updateSummary(eq(SESSION_ID), anyString(), anyLong())).thenReturn(1);

        service.runSummarize(USER_ID, SESSION_ID);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(chatSessionMapper).updateSummary(eq(SESSION_ID), summary.capture(), anyLong());
        assertEquals(120, summary.getValue().length());
    }

    @Test
    void 线程池拒绝时不抛异常且释放单飞占位() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        ChatSummaryServiceImpl rejectingService = new ChatSummaryServiceImpl(chatClient, chatSessionMapper,
                chatMessageMapper, promptTemplateService, sceneModelResolver, properties, new ChatRoundGuard(), rejecting);

        rejectingService.maybeSummarizeAsync(USER_ID, SESSION_ID);
        assertFalse(rejectingService.inFlight(), "提交失败必须释放占位，否则该会话永远不能再触发摘要");

        // 释放之后再提交一次仍应被接受（这里直接验证占位已释放）
        rejectingService.maybeSummarizeAsync(USER_ID, SESSION_ID);
        assertNotNull(rejectingService);
    }

    @Test
    void 同一会话并发触发只跑一次() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenReturn("摘要");
        // 让任务在"执行中"卡住：用一个可控的执行器
        List<Runnable> queued = new ArrayList<>();
        ChatSummaryService queuedService = new ChatSummaryServiceImpl(chatClient, chatSessionMapper,
                chatMessageMapper, promptTemplateService, sceneModelResolver, properties, new ChatRoundGuard(), queued::add);

        queuedService.maybeSummarizeAsync(USER_ID, SESSION_ID);
        queuedService.maybeSummarizeAsync(USER_ID, SESSION_ID);
        queuedService.maybeSummarizeAsync(USER_ID, SESSION_ID);

        assertEquals(1, queued.size(), "同一会话同时只允许一个摘要任务");
    }

    @Test
    void 已有摘要会一并送进模型做合并去重() {
        properties.getMemory().setKeepRecentMessages(6);
        stubMessages();
        when(chatSessionMapper.selectContextSummary(SESSION_ID)).thenReturn("用户早餐前血糖 6.8");
        when(chatSessionMapper.selectSummaryUptoMsgId(SESSION_ID)).thenReturn(null);
        when(callSpec.content()).thenReturn("合并后的摘要");
        when(chatSessionMapper.updateSummary(eq(SESSION_ID), anyString(), anyLong())).thenReturn(1);

        service.runSummarize(USER_ID, SESSION_ID);

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(input.capture());
        assertTrue(input.getValue().contains("已有摘要"), "要把已有摘要一起送进去，让模型合并去重：" + input.getValue());
        assertTrue(input.getValue().contains("用户早餐前血糖 6.8"));
        verify(chatClient, times(1)).prompt();
    }
}
