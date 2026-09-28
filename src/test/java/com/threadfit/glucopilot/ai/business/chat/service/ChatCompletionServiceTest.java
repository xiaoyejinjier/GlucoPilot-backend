package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式补全的收尾语义。
 * <p>
 * 改成返回 {@code Flux} 之后，「谁在什么时候落库」由数据流结构决定，
 * 因此下面几条必须被测试锁住（它们是这套设计与原 {@code SseEmitter} 实现等价的关键）：
 * <ol>
 *   <li>正常结束 → 落库并发出 {@code done}；</li>
 *   <li>上游报错且已有内容 → 落库并标 4（错误），再发 {@code error}；</li>
 *   <li><b>取消</b>（客户端断开，或容器超时后取消订阅）→ <b>不落库</b>、不发事件；</li>
 *   <li>末尾只带 usage 的空正文 chunk → 计量仍要聚合；</li>
 *   <li>落库自身失败 → 只尝试一次，不重复落库。</li>
 * </ol>
 * <p>
 * 注：用 {@code new ChatResponse(List.of(Generation))} 造的假响应，其元数据由框架给出默认值
 * （模型名为空、usage 为 0），所以「模型名回落到配置值」「token 记为 0」都是预期行为。
 */
class ChatCompletionServiceTest {

    private static final long USER_ID = 2L;
    private static final long SESSION_ID = 9L;
    private static final long TARGET_MSG_ID = 1L;

    private final AiChatClientRegistry registry = mock(AiChatClientRegistry.class);
    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);

    private ChatCompletionService service;

    @BeforeEach
    void setUp() {
        service = new ChatCompletionService(registry, chatMessageMapper, chatSessionService,
                chatMessageService, new JsonUtils(new ObjectMapper()));

        when(chatSessionService.requireOwned(anyLong(), anyLong())).thenReturn(new ChatSession());
        when(registry.hasOverrides(SceneCode.CHAT)).thenReturn(false);
        when(registry.modelCode(SceneCode.CHAT)).thenReturn("test-model");

        ChatMessage target = new ChatMessage();
        target.setId(TARGET_MSG_ID);
        target.setSessionId(SESSION_ID);
        target.setMsgRole(CodeEnum.ChatMsgRole.USER.getCode());
        target.setContentText("早餐前血糖 6.8 正常吗");
        when(chatMessageMapper.selectById(TARGET_MSG_ID)).thenReturn(target);
        when(chatMessageMapper.selectAssistantReply(SESSION_ID, TARGET_MSG_ID)).thenReturn(null);

        when(chatMessageService.appendAssistantMessage(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            ChatMessage saved = new ChatMessage();
            saved.setId(99L);
            saved.setReplyToMsgId(invocation.getArgument(2));
            saved.setContentText(invocation.getArgument(3));
            saved.setModelCode(invocation.getArgument(4));
            saved.setPromptTokens(invocation.getArgument(5));
            saved.setCompletionTokens(invocation.getArgument(6));
            saved.setFinishReason(invocation.getArgument(7));
            saved.setLatencyMs(invocation.getArgument(8));
            return saved;
        });
    }

    @Test
    void 正常结束应发出delta与done并落库() {
        useModel(Flux.just(textChunk("你好"), textChunk("，今天")));

        List<ServerSentEvent<byte[]>> events = collect();

        assertEquals(3, events.size());
        assertEquals("delta", events.get(0).event());
        assertTrue(payload(events.get(0)).contains("\"delta\":\"你好\""), payload(events.get(0)));
        assertEquals("delta", events.get(1).event());
        ServerSentEvent<byte[]> done = events.get(2);
        assertEquals("done", done.event());
        assertTrue(payload(done).contains("\"contentText\":\"你好，今天\""), payload(done));
        assertTrue(payload(done).contains("\"finishReason\":1"), payload(done));

        verify(chatMessageService).appendAssistantMessage(eq(USER_ID), eq(SESSION_ID), eq(TARGET_MSG_ID),
                eq("你好，今天"), eq("test-model"), eq(0), eq(0),
                eq(CodeEnum.ChatFinishReason.NORMAL.getCode()), anyInt());
    }

    @Test
    void 上游报错且已有内容应落库标错误并发error事件() {
        useModel(Flux.concat(Flux.just(textChunk("部分内容")),
                Flux.error(new IllegalStateException("上游 500"))));

        List<ServerSentEvent<byte[]>> events = collect();

        assertEquals(2, events.size());
        assertEquals("delta", events.get(0).event());
        assertEquals("error", events.get(1).event());
        assertTrue(payload(events.get(1)).contains("\"code\":50000"), payload(events.get(1)));

        verify(chatMessageService).appendAssistantMessage(eq(USER_ID), eq(SESSION_ID), eq(TARGET_MSG_ID),
                eq("部分内容"), anyString(), any(), any(),
                eq(CodeEnum.ChatFinishReason.ERROR.getCode()), anyInt());
    }

    @Test
    void 取消订阅时不应落库也不应发事件() {
        // 模拟「客户端断开 / 容器超时」：下游只取到第一个 delta 就取消，上游永不完成
        useModel(Flux.concat(Flux.just(textChunk("半截回答")), Flux.never()));

        List<ServerSentEvent<byte[]>> taken = service
                .complete(USER_ID, SESSION_ID, request())
                .take(1)
                .collectList()
                .block();

        assertEquals(1, taken.size());
        assertEquals("delta", taken.get(0).event());
        verify(chatMessageService, never()).appendAssistantMessage(anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void 末尾只带usage的空正文chunk也应聚合计量() {
        // DeepSeek 在最后一个 chunk 才回传 usage，此时正文可能为空——这类 chunk 不能被「空正文」过滤掉
        useModel(Flux.just(textChunk("答"), usageOnlyChunk(11, 7)));

        List<ServerSentEvent<byte[]>> events = collect();

        // 空正文不产生 delta 事件，但计量要落库
        assertEquals(2, events.size());
        verify(chatMessageService).appendAssistantMessage(eq(USER_ID), eq(SESSION_ID), eq(TARGET_MSG_ID),
                eq("答"), eq("test-model"), eq(11), eq(7),
                eq(CodeEnum.ChatFinishReason.NORMAL.getCode()), anyInt());
    }

    @Test
    void 本轮已生成过回复应重放done且不再调用模型() {
        ChatMessage generated = new ChatMessage();
        generated.setId(88L);
        generated.setReplyToMsgId(TARGET_MSG_ID);
        generated.setContentText("上一次的完整回答");
        generated.setFinishReason(CodeEnum.ChatFinishReason.NORMAL.getCode());
        when(chatMessageMapper.selectAssistantReply(SESSION_ID, TARGET_MSG_ID)).thenReturn(generated);

        List<ServerSentEvent<byte[]>> events = collect();

        assertEquals(1, events.size());
        assertEquals("done", events.get(0).event());
        assertTrue(payload(events.get(0)).contains("上一次的完整回答"), payload(events.get(0)));
        verify(registry, never()).client(any());
    }

    @Test
    void 落库失败时error分支不应重复落库() {
        useModel(Flux.just(textChunk("内容")));
        when(chatMessageService.appendAssistantMessage(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("DB 不可用"));

        List<ServerSentEvent<byte[]>> events = collect();

        // 先有一个 delta 事件，落库失败后再由 error 分支补一个 error 事件
        assertEquals(2, events.size());
        assertEquals("delta", events.get(0).event());
        assertEquals("error", events.get(1).event());
        // 只尝试过一次落库：既没有因失败重试，也没有重复插入
        verify(chatMessageService).appendAssistantMessage(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any(), anyInt());
    }

    // ---------- 辅助 ----------

    private List<ServerSentEvent<byte[]>> collect() {
        return service.complete(USER_ID, SESSION_ID, request()).collectList().block();
    }

    /** SSE 载荷是 UTF-8 字节，断言时按 UTF-8 还原。 */
    private static String payload(ServerSentEvent<byte[]> event) {
        return new String(event.data(), StandardCharsets.UTF_8);
    }

    private static ChatDtos.CompletionRequest request() {
        ChatDtos.CompletionRequest request = new ChatDtos.CompletionRequest();
        request.setClientMsgId("c1");
        request.setReplyToMsgId(TARGET_MSG_ID);
        return request;
    }

    private void useModel(Flux<ChatResponse> stream) {
        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本用例只走流式");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream;
            }
        };
        when(registry.client(SceneCode.CHAT)).thenReturn(ChatClient.builder(fakeModel).build());
    }

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 模拟「只回传 usage、正文为空」的收尾 chunk。 */
    private static ChatResponse usageOnlyChunk(int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("test-model")
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""))), metadata);
    }
}
