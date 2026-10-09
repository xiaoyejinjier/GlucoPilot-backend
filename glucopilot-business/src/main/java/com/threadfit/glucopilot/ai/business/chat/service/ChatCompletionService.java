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
import com.threadfit.glucopilot.ai.tool.AiToolContext;
import com.threadfit.glucopilot.ai.vision.VisionAnalyzer;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.entity.MediaFile;
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
import java.util.Map;

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
 * <p>
 * <b>图片轮次（msg_type=2）走两阶段，纯文本轮次不受影响：</b>
 * <ol>
 *   <li><b>阶段 1</b>（{@link VisionAnalyzer}，非流式）：视觉模型看图 → 客观描述。
 *       提示词要求「只描述、不回答」，因为它的产物是中间产物；</li>
 *   <li><b>阶段 2</b>（本类，流式）：把该描述作为本轮用户消息交给文本模型组织回答，
 *       复用 {@link SceneCode#CHAT} 的 client、提示词与全部 Advisor 链。</li>
 * </ol>
 * 这么拆的理由是职责不混：视觉模型只管识别，合规红线/会话上下文/人设统一留在文本链路上。
 * 附带收益是一次视觉调用同时服务两件事——描述既进阶段 2 的上下文，也回填
 * {@code chat_message.media_caption} 让图片进入会话记忆。
 * <p>
 * <b>阶段 1 是同步执行的，代价与取舍：</b>它必须在开流前完成，因此会占用请求线程约 1~3 秒，
 * 且客户端首个 {@code delta} 的到达时间 = 视觉调用 + 文本首字（合计约 4~6 秒，原先约 2~3 秒）。
 * 换来的是错误语义更干净：视觉阶段失败会在<b>开流前</b>抛异常，客户端拿到的是统一 JSON 错误响应，
 * 而不是流到一半再收到 {@code error} 事件。另一个已知代价是：客户端在阶段 1 期间断开时，
 * 这次视觉调用已经花掉了（但因为没订阅，仍不会落库）。
 * <p>
 * <b>回答开头的英文铺垫：靠 {@link EnglishPreambleGuard} 清理，不靠"丢弃工具轮的文本"。</b>
 * <p>
 * 现象：开启工具调用后，回答最前面常多一句"我先查一下……"式的说明句，<b>有时是英文</b>
 * （实测 {@code "I'll check your monthly glucose stats.本月（10 月）血糖情况分析如下"}），
 * 而系统提示词里已经明确禁止英文开头却拦不住。
 * <p>
 * <b>真实流结构（chunk 级日志实测，与直觉不同，值得记住）：</b>
 * <ul>
 *   <li>工具轮的增量正文是<b>空的</b>——工具调用请求本身不产生用户可见文本；</li>
 *   <li>{@code tool_calls} 这个结束原因<b>不会传到业务代码</b>，被 {@code ToolCallingAdvisor}
 *       内部消化掉了；每个 chunk 的 {@code getToolCalls()} 也都是空的；</li>
 *   <li>整条流<b>只有最后一个</b> chunk 带 {@code finish=STOP}。</li>
 * </ul>
 * 也就是说流里<b>不存在</b>"工具轮边界"可用来切掉那句铺垫——它属于最终答案自己这一轮的开头。
 * （曾按"丢弃工具轮文本"实现过一版，因为建立在错误的流模型上而从未生效，已移除。）
 * <p>
 * 所以处理点在<b>正文组装完成后</b>：{@code done.contentText} 与落库正文都经过
 * {@link EnglishPreambleGuard}。前端收到 {@code done} 时用 {@code contentText} 整体替换气泡内容
 * （{@code pages/voice/voice.js} 的 {@code finishRound}），因此最终呈现与下一轮会话记忆都是干净的；
 * 代价是流式过程中仍会短暂闪出那句英文。中文铺垫则保留——"我先查一下"读起来是自然的。
 * <p>
 * 顺带记下另一个被实测否定的设想：曾考虑"缓冲增量、发现是铺垫就不下发"，
 * 但那需要压住增量直到出现中文，会把<b>每一轮</b>的首字延迟都推后（首字已有 2~3 秒），
 * 不值得为偶发的铺垫付这个代价。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionService {

    /** 单条回复落库上限，防异常长文撑爆 TEXT */
    private static final int MAX_REPLY_CHARS = 20_000;

    /** {@code chat_session.media_caption} 是 VARCHAR(1024)，超长在严格模式下会直接抛错，先截断。 */
    private static final int MAX_CAPTION_CHARS = 1024;

    /** 单飞 key 前缀；key 用用户消息 id（全局唯一），因此不需要再拼 sessionId。 */
    private static final String ROUND_KEY_PREFIX = "chat-round:";

    private final AiChatClientRegistry chatClientRegistry;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final OwnedMediaResolver ownedMediaResolver;
    private final VisionAnalyzer visionAnalyzer;
    private final EnglishPreambleGuard englishPreambleGuard;
    private final ChatRoundGuard roundGuard;
    private final JsonUtils jsonUtils;

    public Flux<ServerSentEvent<byte[]>> complete(Long userId, Long sessionId, ChatDtos.CompletionRequest request) {
        // 同步校验：这些异常在返回 Flux 之前抛出，客户端拿到的仍是标准 JSON 错误响应
        chatSessionService.requireOwned(userId, sessionId);
        ChatMessage target = resolveTargetMessage(sessionId, request.getReplyToMsgId());

        // 幂等：本轮已生成过回复则重放 done，既避免重复扣费，也避免前端重试拿到两条助手消息。
        // ⚠️ 必须排在阶段 1 之前：视觉识别是一次又慢又花钱的外部调用，
        //    幂等判断放在它前面，才能保证「重复请求/前端重试」不会重复触发一次图片识别。
        ChatMessage generated = chatMessageMapper.selectAssistantReply(sessionId, target.getId());
        if (generated != null) {
            log.debug("本轮已生成过回复，重放 done sessionId={} replyToMsgId={}", sessionId, target.getId());
            return Flux.just(doneEvent(generated));
        }

        // 单飞：同一条用户消息同时只允许一次生成。
        // 位置很关键——必须在**调用模型之前**：助手消息的客户端幂等键要到 INSERT 时才暴露冲突，
        // 而 INSERT 在模型调用之后，那时钱已经花了（实测两个并发请求 prompt_tokens 5855 + 5940）。
        String roundKey = ROUND_KEY_PREFIX + target.getId();
        if (!roundGuard.tryAcquire(roundKey)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "这一轮正在生成中，请稍后再试");
        }
        Flux<ServerSentEvent<byte[]>> stream;
        try {
            stream = startStream(userId, sessionId, target);
        } catch (RuntimeException e) {
            // 同步阶段（图片轮次的视觉识别、请求组装）抛错时 Flux 还没返回，
            // doFinally 还没挂上——不在这里释放就会永久占位
            roundGuard.release(roundKey);
            throw e;
        }
        // 正常结束、上游报错、客户端取消（框架取消订阅）都会走到这里，保证释放
        return stream.doFinally(signal -> roundGuard.release(roundKey));
    }

    /**
     * 真正开流：组装请求、发起流式调用、接上收尾落库。
     * <p>
     * 与 {@link #complete} 拆开，是为了让"获得执行权 → 可能同步失败的准备工作 → 挂释放回调"
     * 这段控制流写得清楚：同步阶段失败要就地释放，异步阶段失败由 {@code doFinally} 释放。
     */
    private Flux<ServerSentEvent<byte[]>> startStream(Long userId, Long sessionId, ChatMessage target) {
        // 文本轮次取用户原话；图片轮次先跑阶段 1 拿到客观描述，再交给文本模型组织回答。
        TurnInput input = resolveTurnInput(userId, target);

        StreamState state = new StreamState();
        long startNanos = System.nanoTime();

        // 会话历史由 ChatMemory 注入：这里只传「本轮用户消息 + 会话 ID」。
        // 图片轮次同样走 textClient()——两阶段里的阶段 2 就是一次普通文本对话。
        ChatClient.ChatClientRequestSpec promptSpec = chatClientRegistry.textClient()
                .prompt()
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, sessionId.toString()))
                // 工具上下文：当前用户身份由服务端注入，模型看不到也伪造不了。
                // 缺了这一步，工具一旦被调用就会因取不到 userId 而失败（见 AiToolContext）。
                .toolContext(Map.of(AiToolContext.USER_ID, userId))
                .user(input.promptText());
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
                    ChatMessage saved = persist(userId, sessionId, target, input, state, finishReason, latencyMs);
                    log.info("流式补全完成 userId={} sessionId={} assistantMsgId={} latencyMs={} tokens={}/{} finish={}",
                            userId, sessionId, saved.getId(), latencyMs,
                            state.promptTokens, state.completionTokens, finishReason);
                    return doneEvent(saved);
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(deltas, done)
                .onErrorResume(error -> errorEvents(userId, sessionId, target, input, state, startNanos, error));
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

    /**
     * 聚合计量、结束原因与正文。只记状态，不触碰下游——发送由框架负责。
     * <p>
     * 正文<b>逐 chunk 累加</b>，不做轮次划分：实测确认工具轮的增量正文是空的，
     * 切分轮次拿不到任何好处（见类注释里记的真实流结构）。
     * 开头英文铺垫的清理放在正文组装完成之后，由 {@link EnglishPreambleGuard} 负责。
     */
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
    private Flux<ServerSentEvent<byte[]>> errorEvents(Long userId, Long sessionId, ChatMessage target, TurnInput input,
                                                     StreamState state, long startNanos, Throwable error) {
        long latencyMs = elapsedMs(startNanos);
        log.error("流式补全失败 userId={} sessionId={} 已生成 {} 字", userId, sessionId, state.text.length(), error);
        if (!state.persisted && state.text.length() > 0) {
            try {
                persist(userId, sessionId, target, input, state, CodeEnum.ChatFinishReason.ERROR.getCode(), latencyMs);
            } catch (RuntimeException persistError) {
                log.error("失败轮次的部分内容落库失败 userId={} sessionId={}", userId, sessionId, persistError);
            }
        }
        // 图片摘要与「回复是否成功」无关：用户确实发过这张图，摘要值得留住，否则下一轮模型又不记得。
        // 若走的是上面的 persist，摘要已经写过，这里的重复调用会被 SQL 的 media_caption IS NULL 挡掉。
        writeBackCaption(target, input);
        return Flux.just(event("error", ChatDtos.ErrorEvent.builder()
                .code(ErrorCode.INTERNAL_ERROR.getCode())
                .message("AI 服务暂时不可用，请稍后重试")
                .build()));
    }

    private ChatMessage persist(Long userId, Long sessionId, ChatMessage target, TurnInput input, StreamState state,
                                Integer finishReason, long latencyMs) {
        // 先置位再写库：若写库自身失败，错误分支不应再写一次（避免同一轮落出两行）
        state.persisted = true;
        ChatMessage saved = chatMessageService.appendAssistantMessage(
                userId,
                sessionId,
                target.getId(),
                truncate(englishPreambleGuard.apply(state.text.toString())),
                StringUtils.hasText(state.modelCode) ? state.modelCode : chatClientRegistry.modelCode(SceneCode.CHAT),
                state.promptTokens,
                state.completionTokens,
                finishReason,
                (int) Math.min(latencyMs, Integer.MAX_VALUE));
        writeBackCaption(target, input);
        return saved;
    }

    /**
     * 把阶段 1 的图片描述回填到图片消息上，供后续轮次的会话记忆回放。
     * <p>
     * <b>时序很关键：必须在这一轮结束后写，不能提前。</b>
     * 若在阶段 2 之前就写进库，记忆装配会在这条图片消息上回放摘要，
     * 而阶段 2 的当前用户消息里又带着同一份描述，模型会连着看到两遍——
     * 白花 token，还可能让回答变得啰嗦。
     * <p>
     * 写在这里还有个好处：它和助手消息落库处在同一个 {@code Mono} 回调里，
     * 客户端取消/超时时该回调根本不执行，于是<b>取消的轮次也不会写摘要</b>，
     * 与既有的「取消轮次不落库」天然一致，不需要额外兜底。
     * <p>
     * 回填失败只记日志：摘要写不进去，不该让一次已经成功的回复变成错误。
     * 真正保证「只写一次」的是 SQL 里的 {@code AND media_caption IS NULL}。
     */
    private void writeBackCaption(ChatMessage target, TurnInput input) {
        if (input.caption() == null) {
            return;
        }
        try {
            int updated = chatMessageMapper.updateMediaCaption(target.getId(), truncateCaption(input.caption()));
            if (updated == 0) {
                log.debug("图片摘要未回填（已存在或消息已删除）msgId={}", target.getId());
            }
        } catch (RuntimeException e) {
            log.warn("图片摘要回填失败 msgId={}，本轮回复不受影响", target.getId(), e);
        }
    }

    private static String truncateCaption(String caption) {
        return caption.length() <= MAX_CAPTION_CHARS ? caption : caption.substring(0, MAX_CAPTION_CHARS);
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
     * 本轮送入文本模型的输入。
     *
     * @param promptText 交给文本模型的用户消息
     * @param caption    图片轮次下阶段 1 产出的描述；文本轮次为 null（回填时据此判断要不要写库）
     */
    private record TurnInput(String promptText, String caption) {
    }

    /**
     * 解析本轮输入：图片轮次走两阶段，其余走原有的纯文本路径。
     */
    private TurnInput resolveTurnInput(Long userId, ChatMessage target) {
        Integer msgType = target.getMsgType();
        if (msgType != null && msgType == CodeEnum.ChatMsgType.IMAGE.getCode()) {
            return imageTurn(userId, target);
        }
        return new TurnInput(requireUserText(target), null);
    }

    /**
     * 图片轮次：阶段 1（视觉模型识别）→ 组装阶段 2 的用户消息。
     */
    private TurnInput imageTurn(Long userId, ChatMessage target) {
        Long mediaId = target.getMediaId();
        if (mediaId == null) {
            // 与「不是你的」区分开：这是数据完整性问题（正常流程下 2.24 已挡住），
            // 报它出来是为了排障时一眼看出是脏数据而不是权限问题。
            throw new BizException(ErrorCode.BIZ_REJECTED, "图片消息缺少 mediaId，无法生成回复");
        }
        // 归属校验。2.24 已经校验过，这里是真正读取图片字节的入口，再校验一次：
        // 越权的代价是「把别人的图片读出来描述给当前用户」，值得多花一条按主键 + userId + bizType 的查询。
        MediaFile media = ownedMediaResolver.require(mediaId, userId, MediaBizType.CHAT_IMAGE);
        String caption = visionAnalyzer.describe(media, target.getContentText());
        return new TurnInput(stageTwoText(caption), caption);
    }

    /**
     * 阶段 2 的用户消息：把图片描述交给文本模型组织回答。
     * <p>
     * 两点是刻意的：
     * <ul>
     *   <li>明确说明「这是摘要、不是原图，看不到的部分不要臆测」——否则模型容易顺着摘要
     *       把没识别出来的细节编出来；</li>
     *   <li>用户随图片一起发的文字<b>不在这里重复</b>：它在会话历史里会被回放，
     *       重复送一遍等于白花 token。纯图片消息（无文字）的历史里没有这一轮，
     *       所以本段提示语必须自带「请回应」的指令，而不能只丢一段描述过去。</li>
     * </ul>
     * 这里用字符串拼接而不是模板变量：用户消息的文本不参与模板渲染
     * （由 UserTextTemplateRenderingTest 锁定），所以描述里出现花括号也不会抛异常。
     */
    private static String stageTwoText(String caption) {
        return "用户发来了一张图片。下面是你看到的内容摘要（不是原图，摘要里没有的信息不要臆测）：\n"
                + caption
                + "\n请基于以上摘要回应用户。";
    }

    /**
     * 非图片轮次必须有文本才能交给文本模型。
     * 语音（msgType=3）暂时没有可送模型的文本，等 ASR 接入；这里明确拒绝，而不是把空串送给模型。
     */
    private static String requireUserText(ChatMessage target) {
        String text = target.getContentText();
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BIZ_REJECTED,
                    "本轮消息没有文本内容，暂不支持生成回复（语音消息待 ASR 接入后开放）");
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
