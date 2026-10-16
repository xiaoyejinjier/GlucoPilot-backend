package com.threadfit.glucopilot.ai.business.chat.controller;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatMessageService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatSessionService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 对话（接口文档 2.23 / 2.24 / 2.25 / 5.3 / 5.4，外加"会话内历史读取"与"置顶"两条契约外补充）。
 * <p>
 * 注意：2.25 是 SSE，**不能**用 ApiResponse 包装，否则流会被打断；开流后的错误走 error 事件，
 * 开流前的校验失败仍由 GlobalExceptionHandler 返回统一 JSON。
 * <p>
 * 2.25 直接返回 {@code Flux<ServerSentEvent<byte[]>>}，由 MVC 负责 SSE 写出（详见
 * {@code ChatCompletionService} 的类注释）；2.23 / 2.24 仍是普通 JSON 接口。
 * <p>
 * 鉴权由 /api/v1/** 的会话过滤器统一处理，这里用 RequestContext 取当前用户——
 * 这个取值发生在方法体内（请求线程上），不影响 Flux 返回。
 */
@RestController
@RequestMapping("/api/v1/chat-sessions")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final ChatCompletionService chatCompletionService;

    /** 2.23 获取或创建当前会话；{@code forceNew=true} 时先结束旧会话再新建（契约外的最小扩展）。 */
    @PostMapping("/current")
    public ApiResponse<ChatDtos.SessionResponse> current(
            @Valid @RequestBody(required = false) ChatDtos.CurrentSessionRequest request) {
        Long userId = RequestContext.requireUserId();
        Integer sessionType = request == null ? null : request.getSessionType();
        ChatSession session = request != null && Boolean.TRUE.equals(request.getForceNew())
                ? chatSessionService.openNew(userId, sessionType)
                : chatSessionService.getOrCreate(userId, sessionType);
        return ApiResponse.ok(chatSessionService.toResponse(session));
    }

    /**
     * 5.3 会话列表。
     * <p>
     * {@code sessionStatus} 可空（空 = 全部）；排序为置顶优先、其次最近消息时间。
     * 拉列表时会顺带做一次<b>惰性归档</b>（太久没说话的进行中会话置为归档），
     * 因此返回列表里的状态可能与上次看到的不同——这是有意的，见 ChatSessionService#list。
     */
    @GetMapping
    public ApiResponse<ChatDtos.SessionListResponse> list(
            @RequestParam(required = false) Integer sessionStatus,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(chatSessionService.list(userId, sessionStatus, pageNum, pageSize));
    }

    /**
     * 5.4 删除会话（逻辑删除会话及其消息，仅本人；重复删除幂等成功）。
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable Long sessionId) {
        Long userId = RequestContext.requireUserId();
        chatSessionService.delete(userId, sessionId);
        return ApiResponse.ok(null);
    }

    /**
     * 会话内历史消息（契约外补充；UI 打开历史会话必需）。
     * <p>
     * <b>返回的是全部历史</b>，与送进模型的最近 N 条记忆窗口无关（详见 ChatMessageService#readHistory）。
     * 翻页：把上一页返回的 {@code nextBeforeSeq} 传回 {@code beforeSeq}。
     */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<ChatDtos.HistoryResponse> history(@PathVariable Long sessionId,
                                                        @RequestParam(required = false) Integer pageSize,
                                                        @RequestParam(required = false) Integer beforeSeq) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(chatMessageService.readHistory(userId, sessionId, pageSize, beforeSeq));
    }

    /** 置顶/取消置顶（契约只给了 is_pinned 字段，没给接口，这里补最小写接口）。 */
    @PatchMapping("/{sessionId}/pinned")
    public ApiResponse<Void> pinned(@PathVariable Long sessionId,
                                    @Valid @RequestBody ChatDtos.PinnedRequest request) {
        Long userId = RequestContext.requireUserId();
        chatSessionService.setPinned(userId, sessionId, Boolean.TRUE.equals(request.getIsPinned()));
        return ApiResponse.ok(null);
    }

    /** 2.24 发送聊天消息（clientMsgId 幂等）。 */
    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ChatDtos.SendMessageResponse> sendMessage(@PathVariable Long sessionId,
                                                                 @Valid @RequestBody ChatDtos.SendMessageRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(chatMessageService.appendUserMessage(userId, sessionId, request));
    }

    /**
     * 2.25 AI 流式补全（SSE）。
     * <p>
     * 注意：**不要**试图用 {@code produces} 上的 charset 解决中文编码问题——实测响应内容类型
     * 仍是 {@code text/event-stream}（不带 charset），charset 不会进响应头，String 载荷依旧会被
     * 打成 {@code ?}。中文之所以安全，是因为 {@code ChatCompletionService} 发的是 UTF-8 原始字节
     * （{@code ServerSentEvent<byte[]>}），走 {@code ByteArrayHttpMessageConverter} 原样输出，
     * 不依赖任何 charset 判断。详见 {@code ChatCompletionService} 类注释与
     * {@code ChatControllerSseWireTest}。
     */
    @PostMapping(value = "/{sessionId}/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<byte[]>> completions(@PathVariable Long sessionId,
                                                     @Valid @RequestBody ChatDtos.CompletionRequest request) {
        Long userId = RequestContext.requireUserId();
        return chatCompletionService.complete(userId, sessionId, request);
    }
}
