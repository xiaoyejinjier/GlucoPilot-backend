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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话（接口文档 2.23 / 2.24 / 2.25）。
 * <p>
 * 注意：2.25 是 SSE，**不能**用 ApiResponse 包装，否则流会被打断；错误也走 error 事件。
 * 鉴权由 /api/v1/** 的会话过滤器统一处理，这里用 RequestContext 取当前用户。
 */
@RestController
@RequestMapping("/api/v1/chat-sessions")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final ChatCompletionService chatCompletionService;

    /** 2.23 获取或创建当前会话。 */
    @PostMapping("/current")
    public ApiResponse<ChatDtos.SessionResponse> current(
            @Valid @RequestBody(required = false) ChatDtos.CurrentSessionRequest request) {
        Long userId = RequestContext.requireUserId();
        Integer sessionType = request == null ? null : request.getSessionType();
        ChatSession session = chatSessionService.getOrCreate(userId, sessionType);
        return ApiResponse.ok(chatSessionService.toResponse(session));
    }

    /** 2.24 发送聊天消息（clientMsgId 幂等）。 */
    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ChatDtos.SendMessageResponse> sendMessage(@PathVariable Long sessionId,
                                                                 @Valid @RequestBody ChatDtos.SendMessageRequest request) {
        Long userId = RequestContext.requireUserId();
        return ApiResponse.ok(chatMessageService.appendUserMessage(userId, sessionId, request));
    }

    /** 2.25 AI 流式补全（SSE）。 */
    @PostMapping(value = "/{sessionId}/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completions(@PathVariable Long sessionId,
                                  @Valid @RequestBody ChatDtos.CompletionRequest request) {
        Long userId = RequestContext.requireUserId();
        return chatCompletionService.complete(userId, sessionId, request);
    }
}
