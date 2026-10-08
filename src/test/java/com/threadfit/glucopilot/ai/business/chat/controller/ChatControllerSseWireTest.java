package com.threadfit.glucopilot.ai.business.chat.controller;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatMessageService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接口 2.25 改成返回 {@code Flux<ServerSentEvent<byte[]>>} 之后的**线上报文形态**校验。
 * <p>
 * 为什么必须实测：返回 Flux 之后，框架要把 {@code ServerSentEvent} 还原成
 * {@code event:xxx} + {@code data:xxx} 字节流，而这条路径有个真实的坑——
 * {@code adapt()} 调用 {@code .data(payload)} 时<b>不带 media type</b>，
 * 载荷因此落到按响应内容类型选出的转换器上：
 * <ul>
 *   <li>String → {@code StringHttpMessageConverter}，charset 取决于响应内容类型，
 *       实测中文被写成 {@code ?}（{@code data:{"delta":"??"}}）；</li>
 *   <li>byte[] → {@code ByteArrayHttpMessageConverter}，原样输出字节，不受 charset 影响。</li>
 * </ul>
 * 所以生产代码发的是 UTF-8 原始字节，本用例验证它经框架写出后仍能被正确还原。
 * <p>
 * 用 {@link MockMvcWebTestClient} 而不是裸 MockMvc：后者对 {@code SseEmitter} 的异步写出
 * 捕获不可靠（实测只拿到半截报文，停在 {@code event:delta}）。
 */
class ChatControllerSseWireTest {

    private final ChatCompletionService completionService = mock(ChatCompletionService.class);
    private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

    private ChatController controller() {
        return new ChatController(mock(ChatSessionService.class), mock(ChatMessageService.class), completionService);
    }

    private WebTestClient webTestClient() {
        return MockMvcWebTestClient.bindTo(MockMvcBuilders.standaloneSetup(controller()).build()).build();
    }

    @Test
    void 流式补全的中文载荷应经框架写出后仍正确() {
        when(completionService.complete(anyLong(), anyLong(), any())).thenReturn(Flux.just(
                event("delta", ChatDtos.DeltaEvent.builder().delta("你好").build()),
                event("done", ChatDtos.DoneEvent.builder()
                        .assistantMsgId(99L)
                        .contentText("你好，世界")
                        .finishReason(1)
                        .build())));

        RequestContext.setUserId(2L);
        try {
            // String 视图会由 SSE 感知的解码器还原出每个事件的 data 载荷
            FluxExchangeResult<String> result = webTestClient()
                    .post().uri("/api/v1/chat-sessions/9/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue("{\"clientMsgId\":\"c1\",\"replyToMsgId\":1,\"stream\":true}")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                    .returnResult(String.class);

            List<String> parts = result.getResponseBody().collectList().block();
            // 能按事件切成两段，说明 SSE 分帧（空行分隔）是好的
            assertEquals(2, parts.size(), () -> "应还原出两个 SSE 事件，实际：" + parts);
            assertTrue(parts.get(0).contains("\"delta\":\"你好\""),
                    () -> "中文 delta 被破坏（charset 相关），实际：" + parts.get(0));
            assertTrue(parts.get(1).contains("你好，世界"),
                    () -> "中文正文被破坏，实际：" + parts.get(1));

            String contentType = String.valueOf(result.getResponseHeaders().getContentType());
            assertTrue(contentType.contains("text/event-stream"),
                    () -> "SSE 响应类型应为 text/event-stream，实际：" + contentType);
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 把「为什么不能把载荷写成 String」这条坑钉死：同一个转换器，带 charset 写 UTF-8，
     * 不带 charset 就把中文打成问号。将来若有人把 {@code byte[]} 简化成 {@code String}，
     * 这条测试会提醒他先想清楚 charset 问题。
     */
    @Test
    void 不带charset时String载荷会把中文打成问号() throws Exception {
        String json = "{\"delta\":\"你好\"}";
        StringHttpMessageConverter converter = new StringHttpMessageConverter();

        MockHttpOutputMessage withCharset = new MockHttpOutputMessage();
        converter.write(json, MediaType.parseMediaType("text/event-stream;charset=UTF-8"), withCharset);
        assertEquals(json, withCharset.getBodyAsString(StandardCharsets.UTF_8));

        MockHttpOutputMessage withoutCharset = new MockHttpOutputMessage();
        converter.write(json, MediaType.parseMediaType("text/event-stream"), withoutCharset);
        assertFalse(withoutCharset.getBodyAsString(StandardCharsets.UTF_8).contains("你好"),
                "不带 charset 时应复现「中文变问号」，否则该坑的机理已变，需重新评估载荷类型的选择");
    }

    @Test
    void 开流前的校验失败应同步抛出以便转成统一JSON() {
        when(completionService.complete(anyLong(), anyLong(), any()))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "会话不存在或不属于当前用户"));

        ChatDtos.CompletionRequest request = new ChatDtos.CompletionRequest();
        request.setClientMsgId("c1");
        request.setReplyToMsgId(1L);

        RequestContext.setUserId(2L);
        try {
            // 同步抛出（而不是返回一个带 error 事件的 Flux）是「开流前失败仍返回统一 JSON」的前提
            BizException error = assertThrows(BizException.class,
                    () -> controller().completions(9L, request));
            assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        } finally {
            RequestContext.clear();
        }
    }

    private ServerSentEvent<byte[]> event(String name, Object payload) {
        return ServerSentEvent.builder(jsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8))
                .event(name)
                .build();
    }
}
