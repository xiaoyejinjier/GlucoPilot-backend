package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.context.ChatContextAssembler;
import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.config.AiModelInfo;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 流式补全（对应接口 2.25）。
 * <p>
 * 事件：{@code delta} 增量、{@code done} 结束（必带完整正文）、{@code error} 异常。
 * SSE 不包 {@code ApiResponse}；开流之前的校验失败仍走统一 JSON 响应（由 GlobalExceptionHandler 处理）。
 * <p>
 * 时序约定：助手消息在流正常结束（或上游报错且已有部分内容）之后才落库，保证 content_text / tokens / latency 一次写全。
 * <p>
 * 客户端中途断开属于「取消」而不是「出错」：取消不会触发完成回调，因此这一轮不落库，用户重试时会重新生成完整回答。
 * 这比先落半截、之后被幂等重放成残缺回答要合理（实测：curl --max-time 中断后 chat_message 无新增行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionService {

    /** SSE 超时：模型最慢也要在这个时间内收尾，否则释放连接与上游订阅 */
    private static final long SSE_TIMEOUT_MS = 60_000L;
    /** 单条回复落库上限，防异常长文撑爆 TEXT */
    private static final int MAX_REPLY_CHARS = 20_000;

    private final ChatClient chatClient;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatContextAssembler contextAssembler;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final AiModelInfo aiModelInfo;

    public SseEmitter complete(Long userId, Long sessionId, ChatDtos.CompletionRequest request) {
        // 同步校验：这些异常在开流前抛出，客户端拿到的仍是标准 JSON 错误响应
        ChatSession session = chatSessionService.requireOwned(userId, sessionId);
        ChatMessage target = resolveTargetMessage(sessionId, request.getReplyToMsgId());

        // 幂等：本轮已生成过回复则重放 done，既避免重复扣费，也避免前端重试拿到两条助手消息
        ChatMessage generated = chatMessageMapper.selectAssistantReply(sessionId, target.getId());
        if (generated != null) {
            log.debug("本轮已生成过回复，重放 done sessionId={} replyToMsgId={}", sessionId, target.getId());
            SseEmitter replay = new SseEmitter(SSE_TIMEOUT_MS);
            send(replay, "done", toDoneEvent(generated));
            replay.complete();
            return replay;
        }

        List<Message> history = contextAssembler.assemble(session, target);
        if (history.isEmpty()) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "会话没有可用于生成的上下文消息");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 先发注释行把响应头刷出去，避免客户端以为连接没建立
        sendComment(emitter, "connected");

        StreamState state = new StreamState();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        long startNanos = System.nanoTime();

        subscription.set(chatClient.prompt()
                .messages(history)
                .stream()
                .chatResponse()
                .subscribe(
                        chunk -> onChunk(emitter, state, chunk),
                        error -> onError(emitter, userId, sessionId, target, state, startNanos, error),
                        () -> onComplete(emitter, userId, sessionId, target, state, startNanos)));

        emitter.onCompletion(() -> dispose(subscription.get()));
        emitter.onTimeout(() -> {
            log.warn("SSE 超时，取消上游订阅 sessionId={}", sessionId);
            dispose(subscription.get());
            emitter.complete();
        });
        emitter.onError(e -> dispose(subscription.get()));
        return emitter;
    }

    // ---------- 流式回调 ----------

    private void onChunk(SseEmitter emitter, StreamState state, ChatResponse chunk) {
        if (chunk == null) {
            return;
        }
        ChatResponseMetadata metadata = chunk.getMetadata();
        if (metadata != null) {
            if (metadata.getModel() != null) {
                state.modelCode = metadata.getModel();
            }
            Usage usage = metadata.getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) {
                    state.promptTokens = usage.getPromptTokens();
                }
                if (usage.getCompletionTokens() != null) {
                    state.completionTokens = usage.getCompletionTokens();
                }
            }
        }
        Generation generation = chunk.getResult();
        if (generation == null) {
            return;
        }
        if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
            state.providerFinishReason = generation.getMetadata().getFinishReason();
        }
        if (generation.getOutput() == null) {
            return;
        }
        String delta = generation.getOutput().getText();
        if (delta == null || delta.isEmpty()) {
            return;
        }
        state.text.append(delta);
        if (!state.sendFailed) {
            state.sendFailed = !send(emitter, "delta", ChatDtos.DeltaEvent.builder().delta(delta).build());
        }
    }

    private void onComplete(SseEmitter emitter, Long userId, Long sessionId, ChatMessage target,
                            StreamState state, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        Integer finishReason = mapFinishReason(state.providerFinishReason);
        if (finishReason == null) {
            finishReason = CodeEnum.ChatFinishReason.NORMAL.getCode();
        }
        ChatMessage saved = persist(userId, sessionId, target, state, finishReason, latencyMs);
        send(emitter, "done", toDoneEvent(saved));
        emitter.complete();
        log.info("流式补全完成 userId={} sessionId={} assistantMsgId={} latencyMs={} tokens={}/{} finish={}",
                userId, sessionId, saved.getId(), latencyMs, state.promptTokens, state.completionTokens, finishReason);
    }

    private void onError(SseEmitter emitter, Long userId, Long sessionId, ChatMessage target,
                         StreamState state, long startNanos, Throwable error) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.error("流式补全失败 userId={} sessionId={} 已生成 {} 字", userId, sessionId, state.text.length(), error);
        // 已生成的部分仍落库并标 4（错误），避免用户看到的内容凭空消失
        if (state.text.length() > 0) {
            persist(userId, sessionId, target, state, CodeEnum.ChatFinishReason.ERROR.getCode(), latencyMs);
        }
        send(emitter, "error", ChatDtos.ErrorEvent.builder()
                .code(ErrorCode.INTERNAL_ERROR.getCode())
                .message("AI 服务暂时不可用，请稍后重试")
                .build());
        emitter.complete();
    }

    private ChatMessage persist(Long userId, Long sessionId, ChatMessage target, StreamState state,
                                Integer finishReason, long latencyMs) {
        return chatMessageService.appendAssistantMessage(
                userId,
                sessionId,
                target.getId(),
                truncate(state.text.toString()),
                state.modelCode == null ? aiModelInfo.modelCode() : state.modelCode,
                state.promptTokens,
                state.completionTokens,
                finishReason,
                (int) Math.min(latencyMs, Integer.MAX_VALUE));
    }

    // ---------- 工具方法 ----------

    /** 定位本轮要回复的用户消息；未传 replyToMsgId 时取最近一条用户消息。 */
    private ChatMessage resolveTargetMessage(Long sessionId, Long replyToMsgId) {
        ChatMessage target = replyToMsgId == null
                ? chatMessageMapper.selectLatestUserMessage(sessionId)
                : chatMessageMapper.selectById(replyToMsgId);
        boolean valid = target != null
                && sessionId.equals(target.getSessionId())
                && target.getMsgRole() != null
                && target.getMsgRole() == CodeEnum.ChatMsgRole.USER.getCode();
        if (!valid) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "replyToMsgId 无效：该会话下没有对应的用户消息");
        }
        return target;
    }

    /** 供应商结束原因 → DDL 约定的 1/2/3/4；识别不了返回 null（列允许 NULL）。 */
    private static Integer mapFinishReason(String providerFinishReason) {
        if (providerFinishReason == null) {
            return null;
        }
        return switch (providerFinishReason.toLowerCase(Locale.ROOT)) {
            case "stop" -> CodeEnum.ChatFinishReason.NORMAL.getCode();
            case "length" -> CodeEnum.ChatFinishReason.LENGTH.getCode();
            case "content_filter" -> CodeEnum.ChatFinishReason.CONTENT_FILTER.getCode();
            default -> null;
        };
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_REPLY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_REPLY_CHARS);
    }

    private static ChatDtos.DoneEvent toDoneEvent(ChatMessage message) {
        return ChatDtos.DoneEvent.builder()
                .assistantMsgId(message.getId())
                .replyToMsgId(message.getReplyToMsgId())
                .contentText(message.getContentText())
                .finishReason(message.getFinishReason())
                .modelCode(message.getModelCode())
                .promptTokens(message.getPromptTokens())
                .completionTokens(message.getCompletionTokens())
                .latencyMs(message.getLatencyMs() == null ? null : message.getLatencyMs().longValue())
                .build();
    }

    /** @return true 表示已成功写出；false 表示客户端已断开 */
    private static boolean send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            // 客户端已断开：不再尝试发送，收尾交给 emitter 的 onError/onCompletion
            log.debug("SSE 发送失败（客户端可能已断开）：{}", e.getMessage());
            return false;
        }
    }

    private static void sendComment(SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception e) {
            log.debug("SSE 注释发送失败：{}", e.getMessage());
        }
    }

    private static void dispose(Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /**
     * 单轮流式状态。onNext 由同一个订阅串行回调，onComplete 在其之后，
     * 因此无需加锁。
     */
    private static final class StreamState {
        private final StringBuilder text = new StringBuilder();
        private String modelCode;
        private Integer promptTokens;
        private Integer completionTokens;
        private String providerFinishReason;
        /** 客户端断开后置位，避免反复抛异常 */
        private boolean sendFailed;
    }
}
