package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.core.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.core.SceneCode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * AI 流式补全（对应接口 2.25）。
 * <p>
 * 返回 {@code Flux<ServerSentEvent<byte[]>>}，由 Spring MVC 的 {@code ReactiveTypeHandler}
 * 负责 HTTP 侧的 SSE 写出（内部即 {@code SseEmitterSubscriber} + {@code SseEmitter}），
 * 因此本类不再手写 emitter、超时、发送与取消：
 * <ul>
 *   <li>客户端断开或请求超时 → 框架取消订阅 → {@code concatWith(done)} 里的落库<b>不会执行</b>，
 *       「取消/超时轮次不落库」由结构保证，而不是靠几处 dispose 兜住；</li>
 *   <li>超时时间由 {@code spring.mvc.async.request-timeout} 统一配置（原先写在 {@code new SseEmitter(60s)}）；</li>
 *   <li>发送失败、连接异常由框架处理，不再需要 {@code sendFailed} 标记。</li>
 * </ul>
 * <p>
 * <b>为什么载荷是 UTF-8 原始字节（{@code ServerSentEvent<byte[]>}）而不是 POJO / String：</b>
 * MVC 的 {@code adapt()} 把 {@code ServerSentEvent} 转成
 * {@code SseEmitter.event().name(...).data(payload)}，这个 {@code data(payload)} <b>不带 media type</b>，
 * 于是框架会按响应类型 {@code text/event-stream} 找 HttpMessageConverter：
 * <ul>
 *   <li>POJO：Jackson 只支持 {@code application/json}，序列化会失败；</li>
 *   <li>String：落到 {@code StringHttpMessageConverter}，charset 取决于响应内容类型，
 *       <b>实测中文会被写成 {@code ?}</b>（观察到 {@code data:{"delta":"??"}}）；</li>
 *   <li>byte[]：{@code ByteArrayHttpMessageConverter} 支持 {@code * /*} 且原样输出字节，
 *       完全不经过 charset 判断——所以这里发 UTF-8 字节，字节与原先
 *       {@code SseEmitter + MediaType.APPLICATION_JSON} 的产物一致。</li>
 * </ul>
 * <p>
 * <b>事件协议不变</b>：{@code delta} / {@code done} / {@code error}，{@code done} 必带完整正文；
 * 开流前的校验失败仍抛异常，由 GlobalExceptionHandler 返回统一 JSON（HTTP 4xx）。
 * <p>
 * <b>一处有意的退化</b>：原实现先发一个 {@code :connected} 注释行把响应头刷出去，本形态下发不了——
 * {@code ServerSentEvent} 的纯注释会走到 {@code .data(null)}，实测不可行。因此开流的第一个字节
 * 改为第一个 {@code delta} 到达时（首字约 2~3s）。前端不依赖该注释行（{@code utils/sse.js}
 * 明确跳过注释行，页面另有「正在思考…」占位），故对客户端无影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionService {

    /** 单条回复落库上限，防异常长文撑爆 TEXT */
    private static final int MAX_REPLY_CHARS = 20_000;

    private final AiChatClientRegistry chatClientRegistry;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final JsonUtils jsonUtils;

    public Flux<ServerSentEvent<byte[]>> complete(Long userId, Long sessionId, ChatDtos.CompletionRequest request) {
        // 同步校验：这些异常在返回 Flux 之前抛出，客户端拿到的仍是标准 JSON 错误响应
        chatSessionService.requireOwned(userId, sessionId);
        ChatMessage target = resolveTargetMessage(sessionId, request.getReplyToMsgId());
        String userText = requireUserText(target);

        // 幂等：本轮已生成过回复则重放 done，既避免重复扣费，也避免前端重试拿到两条助手消息
        ChatMessage generated = chatMessageMapper.selectAssistantReply(sessionId, target.getId());
        if (generated != null) {
            log.debug("本轮已生成过回复，重放 done sessionId={} replyToMsgId={}", sessionId, target.getId());
            return Flux.just(doneEvent(generated));
        }

        StreamState state = new StreamState();
        long startNanos = System.nanoTime();

        // 会话历史由 ChatMemory 注入：这里只传「本轮用户消息 + 会话 ID」
        ChatClient.ChatClientRequestSpec promptSpec = chatClientRegistry.client(SceneCode.CHAT)
                .prompt()
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, sessionId.toString()))
                .user(userText);
        if (chatClientRegistry.hasOverrides(SceneCode.CHAT)) {
            promptSpec = promptSpec.options(chatClientRegistry.optionsBuilder(SceneCode.CHAT));
        }

        Flux<ServerSentEvent<byte[]>> deltas = promptSpec.stream()
                .chatResponse()
                // 先聚合再过滤：最后一个 chunk 可能只带 usage / 结束原因而正文为空，
                // 若因为「正文为空」提前返回，token 计量就会丢
                .doOnNext(chunk -> aggregate(state, chunk))
                .mapNotNull(this::deltaEvent);

        Mono<ServerSentEvent<byte[]>> done = Mono.fromCallable(() -> {
                    long latencyMs = elapsedMs(startNanos);
                    Integer finishReason = mapFinishReason(state.providerFinishReason);
                    if (finishReason == null) {
                        finishReason = CodeEnum.ChatFinishReason.NORMAL.getCode();
                    }
                    ChatMessage saved = persist(userId, sessionId, target, state, finishReason, latencyMs);
                    log.info("流式补全完成 userId={} sessionId={} assistantMsgId={} latencyMs={} tokens={}/{} finish={}",
                            userId, sessionId, saved.getId(), latencyMs,
                            state.promptTokens, state.completionTokens, finishReason);
                    return doneEvent(saved);
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(deltas, done)
                .onErrorResume(error -> errorEvents(userId, sessionId, target, state, startNanos, error));
    }

    // ---------- 事件构造 ----------

    /** SSE: event=delta；空增量返回 null 由 {@code mapNotNull} 过滤（原实现同样是跳过空增量）。 */
    private ServerSentEvent<byte[]> deltaEvent(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return null;
        }
        String delta = chunk.getResult().getOutput().getText();
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        return event("delta", ChatDtos.DeltaEvent.builder().delta(delta).build());
    }

    private ServerSentEvent<byte[]> doneEvent(ChatMessage message) {
        return event("done", ChatDtos.DoneEvent.builder()
                .assistantMsgId(message.getId())
                .replyToMsgId(message.getReplyToMsgId())
                .contentText(message.getContentText())
                .finishReason(message.getFinishReason())
                .modelCode(message.getModelCode())
                .promptTokens(message.getPromptTokens())
                .completionTokens(message.getCompletionTokens())
                .latencyMs(message.getLatencyMs() == null ? null : message.getLatencyMs().longValue())
                .build());
    }

    private ServerSentEvent<byte[]> event(String name, Object payload) {
        // 载荷用 UTF-8 原始字节而不是 String：SSE 的 data 分片在框架里是不带 media type 的
        // （SseEmitterSubscriber.adapt() 调用 .data(payload)），交给 StringHttpMessageConverter
        // 时 charset 取决于响应内容类型，实测会把中文写成 '?'。ByteArrayHttpMessageConverter
        // 支持 */* 且原样输出字节，彻底绕开 charset 判断。
        return ServerSentEvent.builder(jsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8))
                .event(name)
                .build();
    }

    // ---------- 流式处理 ----------

    /** 聚合计量与结束原因。只记状态，不触碰下游——发送由框架负责。 */
    private static void aggregate(StreamState state, ChatResponse chunk) {
        if (chunk == null) {
            return;
        }
        ChatResponseMetadata metadata = chunk.getMetadata();
        if (metadata != null) {
            // 用 hasText 而不是 != null：测试/框架默认元数据里的 model 是空串，
            // 若直接采信会把空模型名落进 chat_message.model_code
            if (StringUtils.hasText(metadata.getModel())) {
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
        if (delta != null && !delta.isEmpty()) {
            state.text.append(delta);
        }
    }

    /**
     * 上游报错：已有内容则落库并标 4（错误），再发 {@code error} 事件。
     * <p>
     * 这里的落库<b>不能</b>把 error 事件带崩：落库失败只能记日志，客户端仍需要拿到可重试的信号。
     */
    private Flux<ServerSentEvent<byte[]>> errorEvents(Long userId, Long sessionId, ChatMessage target,
                                                     StreamState state, long startNanos, Throwable error) {
        long latencyMs = elapsedMs(startNanos);
        log.error("流式补全失败 userId={} sessionId={} 已生成 {} 字", userId, sessionId, state.text.length(), error);
        if (!state.persisted && state.text.length() > 0) {
            try {
                persist(userId, sessionId, target, state, CodeEnum.ChatFinishReason.ERROR.getCode(), latencyMs);
            } catch (RuntimeException persistError) {
                log.error("失败轮次的部分内容落库失败 userId={} sessionId={}", userId, sessionId, persistError);
            }
        }
        return Flux.just(event("error", ChatDtos.ErrorEvent.builder()
                .code(ErrorCode.INTERNAL_ERROR.getCode())
                .message("AI 服务暂时不可用，请稍后重试")
                .build()));
    }

    private ChatMessage persist(Long userId, Long sessionId, ChatMessage target, StreamState state,
                                Integer finishReason, long latencyMs) {
        // 先置位再写库：若写库自身失败，错误分支不应再写一次（避免同一轮落出两行）
        state.persisted = true;
        return chatMessageService.appendAssistantMessage(
                userId,
                sessionId,
                target.getId(),
                truncate(state.text.toString()),
                StringUtils.hasText(state.modelCode) ? state.modelCode : chatClientRegistry.modelCode(SceneCode.CHAT),
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

    /**
     * 本轮必须有文本才能交给文本模型。
     * 图片（msgType=2）/语音（msgType=3）消息暂时没有可送模型的文本：前者等视觉 provider 接入
     * （场景 {@link SceneCode#CHAT_IMAGE}），后者等 ASR。这里明确拒绝，而不是把空串送给模型。
     */
    private static String requireUserText(ChatMessage target) {
        String text = target.getContentText();
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "本轮消息没有文本内容，暂不支持生成回复（图片/语音消息待多模态与 ASR 接入后开放）");
        }
        return text;
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

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 单轮流式状态。Reactor 保证 doOnNext 与后续算子串行，故无需加锁。
     */
    private static final class StreamState {
        private final StringBuilder text = new StringBuilder();
        private String modelCode;
        private Integer promptTokens;
        private Integer completionTokens;
        private String providerFinishReason;
        /** 本轮是否已落库：防止「落库失败 → 错误分支再落一次」造成重复行。 */
        private boolean persisted;
    }
}
