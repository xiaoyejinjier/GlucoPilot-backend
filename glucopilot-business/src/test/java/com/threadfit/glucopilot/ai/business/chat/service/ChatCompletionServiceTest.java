package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.ai.support.ChatRoundGuard;
import com.threadfit.glucopilot.ai.support.EnglishPreambleGuard;
import com.threadfit.glucopilot.ai.support.OwnedMediaResolver;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

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
 *   <li>落库自身失败 → 只尝试一次，不重复落库；</li>
 *   <li>回答开头的英文句子 → 落库与 {@code done.contentText} 里都不留（见
 *       {@link com.threadfit.glucopilot.ai.support.EnglishPreambleGuard}），
 *       而 {@code delta} 流保持原样。</li>
 * </ol>
 * <p>
 * 注：用 {@code new ChatResponse(List.of(Generation))} 造的假响应，其元数据由框架给出默认值
 * （模型名为空、usage 为 0），所以「模型名回落到配置值」「token 记为 0」都是预期行为。
 */
class ChatCompletionServiceTest {

    private static final long USER_ID = 2L;
    private static final long SESSION_ID = 9L;
    private static final long TARGET_MSG_ID = 1L;
    private static final long IMAGE_MEDIA_ID = 123L;

    /** 记录送进模型的 Prompt，用于断言两阶段拼装结果。 */
    private final List<Prompt> capturedPrompts = new ArrayList<>();

    private final AiChatClientRegistry registry = mock(AiChatClientRegistry.class);
    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
    private final OwnedMediaResolver ownedMediaResolver = mock(OwnedMediaResolver.class);
    private final VisionAnalyzer visionAnalyzer = mock(VisionAnalyzer.class);

    private ChatCompletionService service;

    @BeforeEach
    void setUp() {
        service = new ChatCompletionService(registry, chatMessageMapper, chatSessionService,
                chatMessageService, ownedMediaResolver, visionAnalyzer, new EnglishPreambleGuard(),
                new ChatRoundGuard(), new JsonUtils(new ObjectMapper()));

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
        verify(registry, never()).textClient();
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

    // ---------- 回答开头的英文铺垫 ----------

    /**
     * 模型在回答开头说了一句英文时，<b>落库正文与 {@code done.contentText} 都必须不含这句英文</b>。
     * <p>
     * 实测背景：这句话是<b>最终答案自己这一轮的开头</b>（不是工具轮），提示词明确禁止英文也拦不住
     * （见 {@code EnglishPreambleGuard} 类注释里记的真实流结构与样例）。
     * 前端在 {@code done} 时用 {@code contentText} 整体替换气泡内容，所以这条断言同时决定了
     * 「用户最终看到什么」和「下一轮会话记忆里回放什么」。
     */
    @Test
    void 回答开头的英文句子不应进入正文与落库内容() {
        useModel(Flux.just(
                textChunk("I'll check your monthly glucose stats."),
                textChunk("本月（10 月）血糖情况分析如下："),
                textChunk("平均 8.0 mmol/L。")));

        List<ServerSentEvent<byte[]>> events = collect();

        // delta 流保持原样：流式过程中仍会闪出这句英文（不在增量上拦，是为了不推后首字）
        assertTrue(payload(events.get(0)).contains("I'll check"), payload(events.get(0)));

        ServerSentEvent<byte[]> done = events.get(events.size() - 1);
        assertTrue(payload(done).contains("\"contentText\":\"本月（10 月）血糖情况分析如下：平均 8.0 mmol/L。\""),
                payload(done));
        assertTrue(!payload(done).contains("I'll check"), "done 正文里不应有英文铺垫：" + payload(done));

        verify(chatMessageService).appendAssistantMessage(eq(USER_ID), eq(SESSION_ID), eq(TARGET_MSG_ID),
                eq("本月（10 月）血糖情况分析如下：平均 8.0 mmol/L。"), anyString(), any(), any(),
                eq(CodeEnum.ChatFinishReason.NORMAL.getCode()), anyInt());
    }

    /** 以英文词/单位开头、但后面是中文的正常回答不能被误删（前缀不是一个完整英文句子）。 */
    @Test
    void 以英文缩写或单位开头的回答不应被清理() {
        useModel(Flux.just(textChunk("HbA1c 是糖化血红蛋白，反映近 3 个月的平均血糖。")));

        collect();

        verify(chatMessageService).appendAssistantMessage(eq(USER_ID), eq(SESSION_ID), eq(TARGET_MSG_ID),
                eq("HbA1c 是糖化血红蛋白，反映近 3 个月的平均血糖。"), anyString(), any(), any(),
                eq(CodeEnum.ChatFinishReason.NORMAL.getCode()), anyInt());
    }

    /** 中途报错时，落库的半截内容同样要经过清理（否则错误轮次又会把英文写进记忆）。 */
    @Test
    void 报错落库的半截回答也不应含英文铺垫() {
        useModel(Flux.concat(
                Flux.just(textChunk("Let me check that for you."), textChunk("你的记录如下")),
                Flux.error(new IllegalStateException("上游 500"))));

        collect();

        verify(chatMessageService).appendAssistantMessage(eq(USER_ID), eq(SESSION_ID), eq(TARGET_MSG_ID),
                eq("你的记录如下"), anyString(), any(), any(),
                eq(CodeEnum.ChatFinishReason.ERROR.getCode()), anyInt());
    }

    // ---------- 并发：同一轮不能被生成两次 ----------

    /**
     * 同一条用户消息在途时，第二次请求必须被挡在<b>调模型之前</b>。
     * <p>
     * 实测背景：两个并发 /completions 都通过了幂等回查（那是普通快照读），各自调了一次模型
     * （prompt_tokens 5855 + 5940），最后靠唯一键才把第二条回复挡回去——钱已经花了。
     * 所以这里断言"连模型 client 都没取过"，而不是只看有没有落库。
     */
    @Test
    void 同一轮在途时第二次请求应被拒绝且不触发模型调用() {
        useModel(Flux.never());

        assertNotNull(service.complete(USER_ID, SESSION_ID, request()));

        BizException ex = assertThrows(BizException.class,
                () -> service.complete(USER_ID, SESSION_ID, request()));

        assertEquals(ErrorCode.BIZ_REJECTED, ex.getErrorCode());
        assertEquals("这一轮正在生成中，请稍后再试", ex.getMessage());
        verify(registry, times(1)).textClient();
    }

    /** 一轮正常结束后必须释放执行权，否则同一轮永远无法重试（比不加保护更糟）。 */
    @Test
    void 上一轮结束后应允许重新发起() {
        useModel(Flux.just(textChunk("第一轮")));

        service.complete(USER_ID, SESSION_ID, request()).collectList().block();

        // 第二轮不应再抛"正在生成中"（此时会因为 mock 的幂等回查返回 null 而正常开流）
        assertNotNull(service.complete(USER_ID, SESSION_ID, request()));
    }

    /** 同步阶段抛错时 Flux 还没返回、doFinally 还没挂上，必须在原地释放。 */
    @Test
    void 同步阶段抛错也必须释放执行权() {
        useImageTarget(IMAGE_MEDIA_ID);
        when(ownedMediaResolver.require(IMAGE_MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE))
                .thenThrow(new BizException(ErrorCode.BIZ_REJECTED, "图片文件不存在或不可用"));

        BizException first = assertThrows(BizException.class,
                () -> service.complete(USER_ID, SESSION_ID, request()));
        assertEquals("图片文件不存在或不可用", first.getMessage());

        // 关键：第二次仍然报"图片不可用"，而不是"正在生成中"——说明第一次确实释放了
        BizException second = assertThrows(BizException.class,
                () -> service.complete(USER_ID, SESSION_ID, request()));
        assertEquals("图片文件不存在或不可用", second.getMessage());
    }

    // ---------- 图片轮次（两阶段）----------

    @Test
    void 图片轮次应先识别再回答且描述回填在助手消息落库之后() {
        useImageTarget(IMAGE_MEDIA_ID);
        when(ownedMediaResolver.require(IMAGE_MEDIA_ID, USER_ID, MediaBizType.CHAT_IMAGE))
                .thenReturn(imageMedia(IMAGE_MEDIA_ID));
        when(visionAnalyzer.describe(any(MediaFile.class), any())).thenReturn("一碗牛肉面，汤面，份量约一碗");
        useModelCapturing(Flux.just(textChunk("碳水偏高")));

        List<ServerSentEvent<byte[]>> events = collect();

        // 阶段 1 按「本轮那条图片消息」识别
        verify(visionAnalyzer).describe(any(MediaFile.class), any());
        // 阶段 2 拿到的用户消息里带着描述（文本模型不会直接看到图片）
        assertEquals(1, capturedPrompts.size());
        assertTrue(capturedPrompts.get(0).getContents().contains("一碗牛肉面"),
                "阶段 2 的用户消息应包含图片描述：" + capturedPrompts.get(0).getContents());
        // 描述回填到图片消息那一行本身
        verify(chatMessageMapper).updateMediaCaption(TARGET_MSG_ID, "一碗牛肉面，汤面，份量约一碗");
        assertEquals("done", events.get(events.size() - 1).event());

        // 时序：回填必须发生在助手消息落库之后。
        // 若提前回填，记忆装配会在图片消息上回放摘要，而阶段 2 的当前消息里又带着同一份描述，
        // 模型会连着看到两遍、白花 token——这条断言就是防这个。
        InOrder inOrder = inOrder(chatMessageService, chatMessageMapper);
        inOrder.verify(chatMessageService).appendAssistantMessage(anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), any(), any(), any(), anyInt());
        inOrder.verify(chatMessageMapper).updateMediaCaption(eq(TARGET_MSG_ID), anyString());
    }

    @Test
    void 本轮已生成过回复时不应再调用视觉模型() {
        useImageTarget(IMAGE_MEDIA_ID);
        ChatMessage generated = new ChatMessage();
        generated.setId(99L);
        generated.setReplyToMsgId(TARGET_MSG_ID);
        generated.setContentText("上一次的完整回答");
        when(chatMessageMapper.selectAssistantReply(SESSION_ID, TARGET_MSG_ID)).thenReturn(generated);

        List<ServerSentEvent<byte[]>> events = collect();

        assertEquals(1, events.size());
        assertEquals("done", events.get(0).event());
        // 关键：视觉识别又慢又花钱，幂等判断必须挡在它前面，否则前端每重试一次就重识别一次
        verifyNoInteractions(visionAnalyzer);
        verifyNoInteractions(ownedMediaResolver);
    }

    @Test
    void 文本轮次不应触碰视觉模型也不回填描述() {
        useModel(Flux.just(textChunk("你好")));

        collect();

        verifyNoInteractions(visionAnalyzer);
        verifyNoInteractions(ownedMediaResolver);
        verify(chatMessageMapper, never()).updateMediaCaption(anyLong(), anyString());
    }

    // ---------- 辅助 ----------

    private void useImageTarget(Long mediaId) {
        ChatMessage target = new ChatMessage();
        target.setId(TARGET_MSG_ID);
        target.setSessionId(SESSION_ID);
        target.setMsgRole(CodeEnum.ChatMsgRole.USER.getCode());
        target.setMsgType(CodeEnum.ChatMsgType.IMAGE.getCode());
        target.setMediaId(mediaId);
        when(chatMessageMapper.selectById(TARGET_MSG_ID)).thenReturn(target);
    }

    private static MediaFile imageMedia(Long id) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setStorageKey("4/2026/09/1/abc.jpg");
        media.setMimeType("image/jpeg");
        media.setSizeBytes(2048L);
        return media;
    }

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
        when(registry.textClient()).thenReturn(ChatClient.builder(fakeModel).build());
    }

    /**
     * 与 {@link #useModel} 相同，但把送进模型的 Prompt 记下来。
     * 两阶段的关键断言是「阶段 2 的用户消息里到底有没有那段图片描述」，
     * 而这件事只能从 Prompt 上看到——助手消息落的是模型输出，不是输入。
     */
    private void useModelCapturing(Flux<ChatResponse> stream) {
        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本用例只走流式");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                capturedPrompts.add(prompt);
                return stream;
            }
        };
        when(registry.textClient()).thenReturn(ChatClient.builder(fakeModel).build());
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
