# AI 模块实现说明（结合代码）

> 面向「要读懂/要接手/要改」的人：逐层讲清 `com.threadfit.glucopilot.ai` 下 236 个类的职责、彼此依赖、关键方法，全部对照真实代码。
> 与 `AI对话-简单接入方案.md`（设计动机与取舍）、`AI-社区内容审核设计.md`（审核专项）互补：那两份讲「为什么这么做」，这份讲「现在到底是怎么写的」。

- 代码位置：`glucopilot-business/src/main/java/com/threadfit/glucopilot/ai/`（236 个 java 文件），审核工人在 `…/community/`
- 技术栈：Spring Boot 4.1 / Spring AI 2.0.1 / Java 21 / MyBatis-Plus / Flyway
- 现状：8 个场景、11 个工具、9 份提示词、59 个 AI 测试类；全量 985 用例 0 失败；Flyway v60

---

## 0. 一次对话请求经过哪些类

先看主干，后面的章节都是给这张图填细节。

```
小程序
  │ POST /api/v1/chat-sessions/{sid}/messages      （2.24 落库用户消息）
  ▼
ChatController ──► ChatMessageServiceImpl.appendUserMessage
                     ├─ ChatSessionServiceImpl.requireActive      归属 + 状态
                     ├─ OwnedMediaResolver.require                图片/语音归属
                     ├─ ChatMessageMapper.selectNextSeqNo + insert （seq_no 重试）
                     └─ renameIfDefault                         首句自动命名
  │ POST /api/v1/chat-sessions/{sid}/completions    （2.25 SSE）
  ▼
ChatController ──► ChatCompletionServiceImpl.complete
                     ├─ ① requireActive
                     ├─ ② resolveTargetMessage
                     ├─ ③ selectAssistantReply     幂等重放（先于视觉！）
                     ├─ ④ ChatRoundGuard.tryAcquire 单飞
                     └─ ⑤ startStream ─────────────────────────────┐
                                                                    ▼
                                                    ChatClient（AiChatClientConfig 装配）
                                                       ├─ MedicalGuardAdvisor       order=HIGHEST+100
                                                       └─ ChatMemoryReadAdvisor     order=CHAT_MEMORY
                                                             └─ ChatHistoryAssembler + ChatContextReader
                                     提示词不在这条链上：调用点请求级 .system(...) 注入
                                                              │
                                                              ▼
                                                    DynamicChatModel（按配置解析真实模型）
                                                              │
                                                              ├─ 工具回调：AiToolRegistry → TracingToolCallback
                                                              │                  └─ ToolCallTraceRecorder
                                                              └─ 流式 chatResponse()
                                                                    │
                              doOnNext(aggregate) → mapNotNull(deltaEvent) → SSE 帧
                                                                    │
                              done：persist → ChatContextRecorder.record
                                    → ChatSummaryServiceImpl.maybeSummarizeAsync
                                    → UserChatMemoryServiceImpl.extractAsync（条件触发）
```

四条要点，后面反复出现：

1. **advisor 的先后由 `getOrder()` 决定**，与 `defaultAdvisors(...)` 的传参顺序无关。
2. **提示词不在启动期烤进 client，也不经过 advisor**：`ChatCompletionServiceImpl` 每轮从 `AiPromptTemplateService` 现取一次、请求级 `.system(...)` 注入（后台改了不用重启；注入与固定开销估算复用同一份文本）。
3. **模型不是写死的**，`DynamicChatModel` 每次调用都查运行期快照。
4. **落库与后置动作都在 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 里**，不占用 Reactor 事件线程。

---

## 1. 包结构与各层职责

| 子包 | 内容 | 职责 |
| --- | --- | --- |
| `config` | `AIProperties`、`AiChatClientConfig`、`AiDynamicModelConfig` | 配置绑定 + ChatClient 装配 + 动态模型 Bean（唯一的装配点） |
| `core` | `SceneCode`、`AiChatClientRegistry`、`SceneModelResolver` | 场景枚举、按场景取 client、按场景算模型与参数 |
| `guard` | `MedicalGuardAdvisor` | 医疗安全硬拦截（不调模型直接拒答） |
| `support` | `ChatRoundGuard`、`EnglishPreambleGuard`、`OwnedMediaResolver` | 单飞锁、英文前言清理、媒体归属校验 |
| `business/chat` | controller / dto / entity / mapper / service(+impl) | 会话、消息、SSE 补全、上下文分片落库 |
| `business/meal` | 同上 | 拍照识别饮食 |
| `business/ocr` | 同上 | 血糖仪照片 OCR（异步任务） |
| `business/speech` | controller / dto / service | 语音转写（直连百炼，不走 ChatClient） |
| `memory` | advisor / assembly / config / controller / dto / entity / mapper / repository / service(+impl) | 记忆窗口、长期记忆抽取、摘要、上下文分片元数据 |
| `prompt` | advisor / controller / dto / entity / mapper / service(+impl) | 提示词模板（库优先 + classpath 兜底） |
| `model` | controller / dto / entity / mapper / runtime / service(+impl) | 模型目录、运行期配置快照、动态 ChatModel 工厂、密钥加密 |
| `scene` | controller / dto / entity / mapper / service(+impl) | 场景 → 模型/参数绑定 |
| `tool` | `builtin`(10 个 `*Tools`) / `runtime`(registry·tracing·recorder·context·去重) / `port` / `support` | 模型可调用的工具（Function Calling） |
| `vision` | `VisionAnalyzer` | 多模态识别的统一入口 |

依赖方向是单边的：`business/*` → `core`/`memory`/`prompt`/`tool`/`vision` → `model/runtime` → DB/HTTP。`core` 不反向依赖 `business`。

---

## 2. 装配层：`config/AiChatClientConfig.java`（125 行）

整个模块只有这一个地方 `build()` ChatClient，其余类一律注入。

```java
@Configuration
// 2026-11 协议统一后不再按 provider 选择配置；这里只保留一个"总开关"：
// spring.ai.model.chat=none 表示无 Key 部署，文本 AI 整体关闭（不建 ChatClient，调用给明确提示）。
@ConditionalOnExpression("'${spring.ai.model.chat:}' != 'none'")
public class AiChatClientConfig {

    @Bean("textChatClient")
    public ChatClient textChatClient(ChatClient.Builder autoConfiguredBuilder,
                                     AiPromptTemplateService promptTemplateService,
                                     ChatMemoryReadAdvisor chatMemoryReadAdvisor,
                                     AiToolRegistry toolRegistry,
                                     AIProperties aiProperties) {
        return autoConfiguredBuilder.clone()
                .defaultTemplateRenderer(promptTemplateService.renderer())
                .defaultAdvisors(advisors(...))
                .defaultTools(toolRegistry.callbacks().toArray())
                .build();
    }
```

### 2.1 三个不显眼但致命的点

- **必须从注入的 `ChatClient.Builder` clone**。Spring AI 自动装配的 Builder 上挂着 `ToolCallingAdvisor`（工具真正执行的地方）与观测；用 `ChatClient.builder(model)` 手搓出来的 client 会静默丢掉工具与 tracing——表现为「模型说『我查一下』然后什么都没查」。
- **`defaultTools(...)` 只能是 `ToolCallback[]`**，传 List 不做隐式转换。
- **`ai.tools.allow-list` 为空时 `AiToolRegistry.callbacks()` 返回空数组**：不是「全放开」，是「一个都不注册」（见 §8）。

### 2.2 三个 advisor 与它们的顺序

| Advisor | `getOrder()` | 干什么 | 为什么在这个位置 |
| --- | --- | --- | --- |
| `MedicalGuardAdvisor` | `Ordered.HIGHEST_PRECEDENCE + 100` | 扫最后一条用户消息，命中敏感词直接返回固定拒答，**不进模型** | 最外层：先拦住，省一次调用 |
| `ChatMemoryReadAdvisor` | `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` | 读会话历史/摘要/工具摘要/长期记忆，装成最终 Prompt | 与框架记忆 Advisor 同级，保证在「调模型」的终端 Advisor 之前 |

```java
// 刻意没做的事
// 不写 defaultSystem：系统提示词由调用点每请求注入，启动期烤进去就改不动了
// 不挂 SimpleLoggerAdvisor：它会把对话正文打进日志（含用户血糖等健康数据）
// 不用框架 SafeGuardAdvisor：它扫「含系统提示词的全文」，而系统提示词本身就写着「开药/剂量」，会全体误拦
```

`medicalGuard()` 这个 `@Bean` 可能返回 **null**：`ai.guard.enabled=false` 或敏感词为空时打一条 WARN（「医疗安全硬拦截未启用」）并返回 null，`advisors(...)` 里做非空过滤——即「关掉守卫」是合法配置，不是启动失败。

### 2.3 第二个 ChatClient：`utilityChatClient`

```java
@Bean("utilityChatClient")
public ChatClient utilityChatClient(@Qualifier("glucopilotTextChatModel") ChatModel textChatModel,
                                    AiPromptTemplateService promptTemplateService) {
    return ChatClient.builder(textChatModel)
            .defaultTemplateRenderer(promptTemplateService.renderer())
            .build();
}
```

无工具、无 advisor。摘要、记忆抽取、内容审核、中英翻译都用它：它们要的是「一段文本进、一段文本出」，任何历史注入/提示词替换都会污染输入。注意它绑的是**自动装配的那个 ChatModel**（`glucopilotTextChatModel`），不是 `DynamicChatModel`——这些是后台任务，不该跟着前台会话的记忆与人设走。

> 2026-11 收束时从 `summaryChatClient` 改名而来：它服务的场景从 2 个变成 4 个，名字再叫 summary 会误导后来者。

---

## 3. 入口层：9 个 controller

| 类 | 路由 | 说明 |
| --- | --- | --- |
| `business/chat/controller/ChatController` | `/api/v1/chat-sessions` | 2.23 当前会话、5.3 列表、5.4 删除、历史消息、置顶、2.24 发消息、2.25 SSE |
| `business/meal/controller/MealAnalysisController` | `POST /api/v1/meal-analysis` | 拍照识别饮食 |
| `business/ocr/controller/OcrTaskController` | `/api/v1/ocr-tasks` | 创建任务 / 查任务 |
| `business/speech/controller/SpeechTranscriptionController` | `POST /api/v1/speech-transcriptions` | 语音转写（直连百炼 ASR） |
| `memory/controller/UserChatMemoryController` | `/api/v1/users/me/ai-memories` | 用户自助查看/清空长期记忆 |
| `memory/controller/AdminChatMemoryController` | `/admin/v1/users/{id}/chat-memories` | 管理端查看/删除 |
| `model/controller/AdminAiModelController` | `/admin/v1/ai/models` | 模型目录 CRUD + 连通性测试 |
| `prompt/controller/AdminAiPromptController` | `/admin/v1/ai-prompt-templates` | 提示词 CRUD / 发布 |
| `scene/controller/AdminAiSceneController` | `/admin/v1/ai-scenes` | 场景绑定 |

`ChatController` 的类注释里记了两个硬约束，值得原样保留：

- **SSE 不能用 `ApiResponse` 包**，否则流被打断；开流后的错误走 `error` 事件，开流前的校验失败仍由 `GlobalExceptionHandler` 返回统一 JSON。
- **中文不要靠 `produces` 上的 charset 救**：载荷是 `ServerSentEvent<byte[]>`（UTF-8 原始字节），由 `ByteArrayHttpMessageConverter` 原样写出。改成 String 载荷会变 `?`，这是踩过的坑（见 `ChatControllerSseWireTest`）。

---

## 4. 会话与消息：`ChatSessionServiceImpl` / `ChatMessageServiceImpl`

### 4.1 状态机先于一切

`ChatSessionServiceImpl.requireActive(userId, sessionId)` 是「能不能继续写」的唯一判据：不属于当前用户按 404；已结束/已归档抛业务异常。`getOrCreate` / `openNew` / `list` / `delete` / `setPinned` 都先过 `requireOwned`。

`openNew(...)` 在结束旧会话后**旁路**触发记忆抽取：

```java
// 结束旧会话 → 旁路抽取（失败不影响新会话创建）
extractMemoryQuietly(userId, prev.getId(), "session-ended");
```

`extractMemoryQuietly` 再兜一层 try/catch——记忆是增强项，不能让它的异常回滚「开新会话」这个主操作。

`list(...)` 会顺带做一次**惰性归档**（太久没说话的进行中会话置为归档），所以列表里的状态可能与客户端上次看到的不同，这是有意的。

### 4.2 发消息：幂等 + 顺序号

```java
@Transactional
public ChatDtos.SendMessageResponse appendUserMessage(Long userId, Long sessionId, ChatDtos.SendMessageRequest request) {
    chatSessionService.requireActive(userId, sessionId);   // 归属 + 状态
    validatePayload(request);                              // 文本要正文、图片要 mediaId、语音要 voiceMediaId
    requireOwnedMedia(userId, request);                    // 媒体必须属于本人且业务类型匹配

    ChatMessage message = new ChatMessage();
    ...
    try {
        insertWithSeqNo(message);
    } catch (DuplicateKeyException e) {
        if (SqlDuplicateKeys.isDuplicateKeyFor(e, ChatMessageMapper.UK_SESSION_SEQ)) {
            throw e;                       // 顺序号重试耗尽 = 真冲突
        }
        if (!SqlDuplicateKeys.isDuplicateKey(e)) { throw e; }
        // clientMsgId 重复：当作成功，返回既有消息（不重复计数、不重复扣费）
        return toResponse(chatMessageMapper.selectByClientMsgId(sessionId, request.getClientMsgId()));
    }
    chatSessionMapper.touchOnMessage(sessionId, LocalDateTime.now());
    chatSessionService.renameIfDefault(sessionId, request.getContentText());  // 首句自动命名
    return toResponse(message);
}
```

顺序号是「展示用的连续编号」，不用自增也不用 Redis：

```java
private void insertWithSeqNo(ChatMessage message) {
    for (int attempt = 1; ; attempt++) {
        message.setSeqNo(chatMessageMapper.selectNextSeqNo(message.getSessionId()));
        try { chatMessageMapper.insert(message); return; }
        catch (DuplicateKeyException e) {
            if (!SqlDuplicateKeys.isDuplicateKeyFor(e, ChatMessageMapper.UK_SESSION_SEQ)) throw e;
            if (attempt >= SEQ_NO_RETRY) throw e;   // 重算必然前进的前提：selectNextSeqNo 不过滤 is_deleted
        }
    }
}
```

注释里那句「重算必然前进」是关键：`selectNextSeqNo` **不能过滤 `is_deleted`**，否则软删掉最大号那条之后，每次重算都得到同一个号，重试永远失败。

---

## 5. 流式补全：`ChatCompletionServiceImpl`（662 行）

模块里最长的一个类，`complete()` 的语句顺序就是控制流，每一步都有事故背景。

### 5.1 `complete()` 的七步

```java
public Flux<ServerSentEvent<byte[]>> complete(Long userId, Long sessionId, ChatDtos.CompletionRequest request) {
    // ① 会话可用
    chatSessionService.requireActive(userId, sessionId);
    // ② 找到本轮要回复的那条用户消息
    ChatMessage target = resolveTargetMessage(sessionId, request.getReplyToMsgId());
    // ③ 幂等重放：这一轮已经生成过就直接回放 done 事件
    ChatMessage generated = chatMessageMapper.selectAssistantReply(sessionId, target.getId());
    if (generated != null) { return Flux.just(doneEvent(generated)); }
    // ④ 单飞：同一轮只允许一个生成
    String roundKey = ROUND_KEY_PREFIX + target.getId();
    if (!roundGuard.tryAcquire(roundKey)) {
        throw new BizException(ErrorCode.BIZ_REJECTED, "这一轮正在生成中，请稍后再试");
    }
    Flux<ServerSentEvent<byte[]>> stream;
    try {
        stream = startStream(userId, sessionId, target);
    } catch (RuntimeException e) {
        roundGuard.release(roundKey);   // ⑤ 同步异常要还锁
        throw e;
    }
    return stream.doFinally(signal -> roundGuard.release(roundKey));  // ⑥ 正常/异常/取消都还锁
}
```

四个「为什么顺序不能换」：

- **③ 必须在 ④ 之前、也在视觉识别之前**。视觉识别是慢且贵的外部调用，重放路径不该再付一次钱。
- **④ 必须在调模型之前**。实测并发两个请求会各花一次，`prompt_tokens` 5855 + 5940。
- **⑤ 和 ⑥ 都要有**：`startStream` 里同步抛错时流还没被订阅，`doFinally` 不会触发，锁会永久泄漏。
- `ROUND_KEY_PREFIX = "chat-round:"` 与工具调用记账用的是同一个 key 前缀（见 §8），天然对齐「一轮」。

### 5.2 `startStream()`：请求是怎么拼出来的

```java
TurnInput input = resolveTurnInput(userId, target);   // 文本轮 = 原话；图片轮 = 两阶段（§12）

Map<String, Object> toolContext = new HashMap<>();
toolContext.put(AiToolContext.USER_ID, userId);
toolContext.put(AiToolContext.SESSION_ID, sessionId);
toolContext.put(AiToolContext.USER_MSG_ID, target.getId());
toolContext.put(AiToolContext.CALL_COUNTER, new AtomicInteger());   // 必须是可变 map，Map.of 放不进计数器

int fixedOverhead = estimateFixedOverhead();
AtomicInteger promptEstimate = new AtomicInteger();
AtomicReference<List<ChatContextShard>> contextShards = new AtomicReference<>();

ChatClient.ChatClientRequestSpec spec = chatClientRegistry.textClient().prompt()
        .advisors(a -> a
                .param(ChatMemory.CONVERSATION_ID, sessionId.toString())
                .param(FIXED_OVERHEAD_TOKENS, fixedOverhead)
                .param(USER_ID, userId)
                .param(PROMPT_ESTIMATE, promptEstimate)
                .param(CONTEXT_SHARDS, contextShards))
        .toolContext(toolContext)
        .user(input.promptText());

if (chatClientRegistry.hasOverrides(SceneCode.CHAT)) {
    spec = spec.options(chatClientRegistry.optionsBuilder(SceneCode.CHAT));
}

return spec.stream().chatResponse()
        .doOnNext(chunk -> aggregate(state, chunk))       // 先聚合
        .mapNotNull(this::deltaEvent)                     // 再转 SSE 帧（末帧可能只带 usage，无文本）
        ...
```

`advisors(a -> a.param(...))` 这五个参数是**跨类契约**，四个消费方：

| 参数 | 写入方 | 读取方 | 用途 |
| --- | --- | --- | --- |
| `ChatMemory.CONVERSATION_ID` | 这里 | `ChatMemoryReadAdvisor.withMemory` | 取哪条会话的历史；缺失即「无状态调用」 |
| `FIXED_OVERHEAD_TOKENS` | 这里 | `ChatHistoryAssembler` | 系统提示词/工具 schema 的固定开销 |
| `USER_ID` | 这里 | `ChatContextReader.longTermMemory` | 取长期记忆 |
| `PROMPT_ESTIMATE` | 这里 | `ChatMemoryReadAdvisor.writeBackEstimate` | 回传估算 token（响应槽） |
| `CONTEXT_SHARDS` | 这里 | `ChatMemoryReadAdvisor.writeBackShards` | 回传分片清单，供落库（响应槽） |

后两个是**回传槽**：Advisor 在装配时把结果写进这个 `AtomicInteger` / `AtomicReference`，Service 在 `persist` 之后读出来落库。为什么不用请求属性？因为值是在 Advisor 里算出来的，Service 需要拿到结果——用可变对象做槽最省事，且天然按请求隔离。

### 5.3 done 分支：落库与两个后置动作

```java
Mono<ServerSentEvent<byte[]>> done = Mono.fromCallable(() -> {
    long latencyMs = System.currentTimeMillis() - state.startedAt;
    int finishReason = mapFinishReason(state.providerFinishReason);   // null → CodeEnum.ChatFinishReason.NORMAL
    state.estimatedPromptTokens = promptEstimate.get();
    ChatMessage saved = persist(userId, sessionId, target, input, state, finishReason, latencyMs);
    calibrateEstimate(state);                                         // 用真实 usage 校准估算系数
    log.info(...);
    chatSummaryService.maybeSummarizeAsync(userId, sessionId);        // 单飞、异步，超阈值才做
    if (UserChatMemoryService.looksExplicit(input.promptText())) {
        userChatMemoryService.extractAsync(userId, sessionId, "explicit");   // 用户说了「记住…」才抽
    }
    return doneEvent(saved);
}).subscribeOn(Schedulers.boundedElastic());
```

- **`persist` 在 done 里而不是 delta 里**：只有流正常结束才写助手消息，中途断开不落半截。
- **`state.persisted` 防重**：`doFinally` 之外的多路径（取消、异常）共用这个标志。
- **`calibrateEstimate`**：把本轮真实 `prompt_tokens` 与估算值喂给 `TokenEstimator.calibrate`，越用越准（3~5 轮收敛）。
- **两个后置动作都「不阻塞、不抛」**：摘要在自己线程池里跑；记忆抽取只对「显式嘱托」触发（`looksExplicit`），否则每轮都抽会烧钱。

### 5.4 其余方法一览

| 方法 | 关键点 |
| --- | --- |
| `resolveTurnInput` / `imageTurn` / `stageTwoText` | 文本轮直传；图片轮先跑阶段 1 出描述、回写 caption，再按阶段 2 模板拼文本 |
| `persist` | `state.persisted = true` 先置位再写库（写库失败时错误分支不会再落一行）；正文过 `EnglishPreambleGuard.apply(...)` 再截断，`modelCode` 缺失时回填场景解析值；落库后才 `writeBackCaption` + `recordContext`（assistant_msg_id 只有这时才有） |
| `estimateFixedOverhead` / `cacheFixedOverhead` | 系统提示词 + 工具 schema 的 token 数，按「提示词版本 + 工具集指纹」缓存，`volatile int` + `fixedOverheadFingerprint` |
| `aggregate` | 累加文本、记 modelCode（首帧 `response.getMetadata().getModel()`）、累加 usage |
| `deltaEvent` / `doneEvent` / `event` / `errorEvents` | 统一 SSE 帧格式；错误事件带可读文案，不泄堆栈 |
| `writeBackCaption` | 图片轮把视觉描述回写到消息，供历史重放（否则下次进模型是空图占位） |
| `mapFinishReason` | 厂商 finish_reason → 内部枚举，null 当 NORMAL |
| `record StreamState(...)` | `text`/`modelCode`/`promptTokens`/`completionTokens`/`providerFinishReason`/`persisted`/`estimatedPromptTokens`/`contextShards` |

---

## 6. 上下文装配：读侧三件套 + 写侧一个记录器

这是整个模块最容易看晕的地方，拆成「读」和「写」两条线。

### 6.1 读侧：Advisor 拿四份原料，Assembler 装成 Prompt

```java
// memory/advisor/ChatMemoryReadAdvisor.java
public static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

public CallAdvisor.Builder adviseCall(Chain chain) { return chain.nextCall(withMemory(chain.request())); }

private ChatRequest withMemory(ChatRequest request) {
    String sid = (String) request.context().get(ChatMemory.CONVERSATION_ID);
    if (sid == null) { return request; }          // 无会话 ID = 无状态调用（比如摘要自己），原样放过

    List<Message> history = chatMemory.get(sid);
    String summary       = contextReader.summary(sid);
    List<String> digests = contextReader.recentToolDigests(sid);
    List<String> memories = contextReader.longTermMemory(userId);   // userId 来自 context，不碰 RequestContext

    Assembled assembled = assembler.assemble(history, current, summary, digests, memories, fixedOverhead);
    writeBackEstimate(assembled.stats().estimatedTokens());   // → AtomicInteger 槽
    writeBackShards(assembled.shards());                      // → AtomicReference 槽
    return request.mutate()
            .prompt(new Prompt(assembled.messages(), request.prompt().getOptions()))
            .context(request.context())
            .build();
}
```

> **红线**：Advisor 的回调不在请求线程上，**绝对不能碰 `RequestContext`**（ThreadLocal）。userId 只能从 `request.context()` 的 advisor 参数里拿。

### 6.2 `ChatHistoryAssembler`（329 行）：预算怎么花

```java
public record Assembled(List<Message> messages, Stats stats, List<ChatContextShard> shards) {}

public record Stats(int keptHistory, int droppedHistory, int estimatedTokens, int budgetTokens,
                    boolean summaryUsed, boolean toolDigestUsed) {
    public boolean budgetExceeded() { return droppedHistory > 0; }
}
```

算法（`assemble`）：

1. `budget = max(ai.memory.max-prompt-tokens, 1)`；
2. 三段附加内容**各用一个 StringBuilder**（`memorySection` / `summarySection` / `toolSection`）——分开是为了按整段估算 token，最后合并成**一条** SystemMessage（多条系统消息会让模型反复权衡）；
3. `used = fixedOverhead + estimate(contextMessage) + Σ estimate(current)`；
4. **从最新往回装**：`kept.size() < windowMessages` 且（`kept.size() < keepRecentMessages` 无条件保底 **或** `used + cost <= budget`），否则 break；
5. 反转成时间正序；
6. 先把本轮里的 `SystemMessage`（调用点请求级 `.system(...)` 注入的场景提示词）拆出来留待放最前——调用方已经把它放进了 `current`，而 `current` 是"本轮新增消息"，直接接在历史后面会让人设与医疗约束落到历史之后；
7. `dropTrailingDuplicate(kept, currentBody)`：历史末尾若与本轮用户消息重复则去掉一条（token 也要跟着扣，否则分片之和比真实 prompt 多一条）；
8. 按 `[场景提示词, 附加上下文, 历史, 本轮非系统消息]` 重排；
9. `buildShards(...)` 产出分片清单，顺序固定「系统提示 → 长期记忆 → 摘要 → 工具摘要 → 历史消息」，与上面的消息顺序一致。

**顺序由两处共同保证**：`ChatClient` 把调用点注入的 `.system(...)` 放在消息列表第一条，本类负责拼历史时把它从「本轮新增」里拆出来、留在最前。只改一边就会出现「首轮正常、第二轮起提示词落到历史后面」——曾经的真实缺陷就是这样漏过 940 个用例的（两边的单测各自都只检查自己那一半），回归用例是 `AdvisorChainOrderTest`（把「已带 system 的请求」整条喂给记忆顾问后断言最终 prompt）。

「保底条数优先」= `keepRecentMessages` 之内的消息即使超预算也保留：最近几轮是语义连贯的最小成本，宁可超一点也不能断在这儿。日志里那句「超预算：保底条数优先」就出自这里。

### 6.3 `ChatContextReader`：读不到就降级，永不抛

```java
public String summary(Long sessionId)            // selectContextSummary；异常/空 → null
public List<String> recentToolDigests(Long sid)  // toolReplayEnabled=false 或 maxItems<=0 → 空
public List<String> longTermMemory(Long userId)  // userMemoryService.forInjection(userId)
```

`recentToolDigests` 取最近 N 条工具调用，**倒序取出后反转成时间正序**，格式 `toolName + " → " + resultDigest`——给模型看的是「调过什么、结论是什么」，不是原始返回。

三条读路径全部「读失败 = 空」：记忆与摘要都是增强项，让它们把主流程打挂是本末倒置。

### 6.4 `TokenEstimator`：会自己校准的估算器

```java
private static final double SMOOTHING = 0.3, MIN_FACTOR = 0.5, MAX_FACTOR = 2.5;
private static final int PER_MESSAGE_OVERHEAD = 4;
private final AtomicLong factorMilli = new AtomicLong(1000);   // 初始 1.0，避免除零

public int estimate(String text);            // 中日韩字符按 1 token/字，其余按字符数/系数
public int estimateMessage(String text);     // estimate + PER_MESSAGE_OVERHEAD
public void calibrate(int actual, int estimated) { ... }       // 指数滑动，夹在 [0.5, 2.5]
```

它存在的理由：真实 token 只在响应里拿到（而且是**上一轮**的），本轮必须先猜。猜准的意义是「装配时不误裁」，猜不准也不致命——所以有 `MIN_FACTOR/MAX_FACTOR` 兜底，防止一次异常 usage 把系数带飞。

### 6.5 写侧：`ChatContextRecorder` 落分片

```java
public interface ChatContextRecorder {
    void record(Long userId, Long sessionId, Long triggerMsgId, Long assistantMsgId, ...);
}
```

`ChatContextRecorderImpl`（120 行）：

```java
public static Integer roundOf(Integer assistantSeqNo) { return (assistantSeqNo + 1) / 2; }
```

轮次号由助手消息 `seq_no` 反推（用户/助手成对，所以 `(seq+1)/2`），不再单独维护一个轮次计数。每轮 1~5 行，`content_text`/`content_json` 恒 NULL（V28 删掉了 `chk_cc_payload` 约束），只存**元数据**：分片类型、token 估算、条数、是否用了摘要/工具摘要。这样「上下文到底装了些什么」可查，又不把用户健康文本二次落库。

---

## 7. 提示词：`AiPromptTemplateServiceImpl` + 调用点请求级注入

### 7.1 双源 + 兜底链

```java
@PostConstruct
void init() {
    for (SceneCode scene : SceneCode.values()) { loadBuiltin(scene); }   // ① classpath 全量预载
    load();                                                             // ② 再读库覆盖
}

public String resolve(SceneCode scene) {
    // 库表快照 → classpath 兜底 → IllegalStateException（宁可启动失败，不让模型裸奔）
}
```

`loadBuiltin` 不是「随便读个文件」：

```java
ClassPathResource resource = new ClassPathResource(aiProperties.scene(scene).getPrompt());
// 必须存在且非空
PromptTemplate tpl = PromptTemplate.builder().template(text).renderer(renderer).build();
AiPromptTemplateService.validateRenderable(text, config.getVariables(), "classpath:" + path);
```

`validateRenderable` 会在启动期校验模板里的 `{xxx}` 占位符**必须在 yaml 声明过**。这条规则救过两次启动：提示词里写了示例 JSON，`{` 被当成占位符 → 启动失败。

### 7.2 硬约束：提示词正文里不能出现成对花括号

这是踩了三次的坑（V22/V23/V27 都因此启动失败过）。原因链：

1. `PromptTemplate` 用 StringTemplate 渲染，`{` `}` 是占位符语法；
2. 逃逸很难看且容易漏（`\{\{` 在 ST 里行为不直觉）；
3. 结果：**所有输出协议都用逐行字段清单，不用 JSON**。

审核提示词就是典型：模型输出

```
结论：REJECT
类别：medical_advice
严重度：3
```
而不是 `{"verdict": "REJECT"}`。解析侧 `ContentModerationParser` 因此写成逐行 `key：value`，容忍中英文冒号、`=`、Markdown。

### 7.3 注入点：调用点请求级，而不是 advisor

```java
// ChatCompletionServiceImpl#startStream
String systemPrompt = promptTemplateService.systemPrompt(SceneCode.CHAT);
int fixedOverhead = estimateFixedOverhead(systemPrompt);   // 复用同一份文本，避免刷新瞬间两次取值不一致
...
textClient().prompt()
        .system(systemPrompt)     // ← 提示词在这里进请求，ChatClient 会把它放在消息列表第一条
        .advisors(...)            // 只有硬拦截与记忆装配；提示词不在这条链上
        .user(...)
```

**为什么不再用 advisor**：advisor 版本的全部价值只有「替换掉已存在的 SystemMessage」，那是不报错的隐式契约；而提示词本身没有跨轮次状态，唯一调用点每轮读一次更直白。代价是「漏写 `.system(...)` 不会报错、只会静默丢人设」，所以 `ChatCompletionServiceTest.发给模型的请求首条必须是当前场景提示词` 断言首条是 `SystemMessage` 且 `systemPrompt(CHAT)` 只被读一次。

后台改提示词 → `AiPromptTemplateService.refreshNow()` → 下一次请求自动生效，**不重启**。

---

## 8. 工具链

### 8.1 三个类的关系

```
AiToolRegistry.callbacks()                    ← 装配期传给 defaultTools(...)
   └─ 对每个放行的 ToolCallback 套一层
        TracingToolCallback(callback, recorder)   ← 装饰器：记账，然后原样调用
             └─ ToolCallTraceRecorder.record(...) ← 写 chat_tool_call
```

```java
// tool/AiToolRegistry.java
public ToolCallback[] callbacks() {
    List<ToolCallback> allow = 按 ai.tools.allow-list 过滤（白名单为空 → 一个都不注册 + info 日志）
    warnAboutMissingBeans(allow);                       // 白名单里找不到的名字 WARN
    return allow.stream().map(cb -> new TracingToolCallback(cb, recorder)).toArray(ToolCallback[]::new);
}
```

`registered()` 合并 `ObjectProvider<ToolCallbackProvider>` 与 `ObjectProvider<ToolCallback>`，按 `getToolDefinition().name()` 去重（同名后者丢弃并 WARN），`getToolCallbacks()` 返回 null 时按空流处理——不加这条，启动期就 NPE。

### 8.2 装饰器的两条纪律

```java
// TracingToolCallback
@Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }   // 必须透传
@Override public ToolMetadata getToolMetadata()     { return delegate.getToolMetadata(); }

public String call(String input) { return delegate.call(input); }   // 无 ToolContext：不记账，只执行

public String call(String input, ToolContext ctx) {
    try { String r = delegate.call(input, ctx); recorder.record(ctx, name(), true, elapsed, null, r); return r; }
    catch (RuntimeException e) { recorder.record(ctx, name(), false, elapsed, e.getClass().getSimpleName(), null); throw e; }
}
```

- `getToolDefinition`/`getToolMetadata` 不透传，框架就判断不出「结果要不要回喂模型」。
- 记账失败**绝不打断工具**（`ToolCallTraceRecorder` 整段 try/catch 只 WARN）。

```java
// ToolCallTraceRecorder.record
Long sessionId  = AiToolContext.optionalLong(ctx, SESSION_ID);   // 缺失 → debug 跳过
Long userMsgId  = AiToolContext.USER_MSG_ID;
String roundKey = "chat-round:" + userMsgId;                     // 与 ChatCompletionServiceImpl 同前缀
int callIndex   = ((AtomicInteger) ctx.get(CALL_COUNTER)).incrementAndGet();   // 按轮隔离
chatToolCallMapper.insert(new ChatToolCall(... ok ? 1 : 0, durationMs, errorType,
        ok ? digester.digest(result, ai.memory.tool-digest-max-chars) : null));   // 原文不落库
```

`CALL_COUNTER` 放在 `toolContext` 里而不是类字段，是刻意的：并发两轮各数各的，天然隔离。

### 8.3 现有 11 个工具（10 个类）

第一批（明细类，回答「具体是哪条」）：

| 类 | 工具名 | 用途 |
| --- | --- | --- |
| `GlucoseQueryTools` | `query_glucose_range` | 按日期区间/最近 N 天查血糖（≤31 天、≤60 条） |
| `StatisticsTools` | `get_daily_checkin` | 当日打卡与达标情况 |
| `StatisticsTools` | `get_month_stats` | 月度统计（打卡率 + 血糖达标率） |
| `MealRecordTools` | `query_meal_records` | 查饮食记录（≤7 天） |
| `FoodNutritionTools` | `lookup_food_nutrition` | 查食物营养库，**一次 1~5 个名字**、每个名字 ≤3 条候选；查不到的名字逐条说明原因 |
| `HealthProfileTools` | `get_health_profile` | 读健康档案（慢病、目标等） |

第二批（结论类，回答「这段时间怎么样」「以前说过什么」）：

| 类 | 工具名 | 用途 |
| --- | --- | --- |
| `GlucoseStatsTools` | `query_glucose_stats` | 只给聚合：达标率/偏高偏低计数/均值/极值/变异系数/按时段与周的走势（≤92 天，**不返回明细**） |
| `ChatHistoryTools` | `search_chat_history` | 在本人历史会话里按关键词找片段（≤365 天、≤10 条、按会话去重） |
| `SessionStateTools` | `get_session_state` | 当前会话的状态：摘要、已调用过的工具及结论摘要 |
| `ReminderTools` | `list_reminders` | 今日提醒（服药/测糖等）与待回复的回访 |
| `ArticleSearchTools` | `search_community_articles` | 检索社区文章（与 feed 同一套可见性过滤，只给摘要与作者） |

**明细与聚合分开**是刻意的：`query_glucose_range` 用来回答「3 号早上多少」，`query_glucose_stats` 用来回答「这个月控制得怎么样」。前者逐条返回（有上限），后者只给聚合且**不返回 items**——两者都能回答问题时优先用聚合，省上下文。

### 8.4 重复调用治理：`ToolRepeatGuard` + `RepeatAwareToolCallback`

模型很容易把同一个查询问两遍（换了个说法、或者忘了自己刚查过）。两层做法：

1. **工具层去重**（兜底）：`AiToolRegistry` 把每个工具包成
   `TracingToolCallback(RepeatAwareToolCallback(callback, guard), traceRecorder)`。
   `RepeatAwareToolCallback` 用 `sessionId + 工具名 + 规范化入参` 做键，命中就**直接返回上次的 JSON**（debug 日志写明命中），未命中才执行并记住。没有 `sessionId`（后台任务）时直接透传，不做去重。
   `ToolRepeatGuard` 的护栏：TTL 30 分钟、每会话最多 16 条、最多 512 个会话、单条结果 ≤8000 字符（过大不缓存），双层 LRU。
   注意包装顺序：`TracingToolCallback` 在**外层**，所以命中缓存的那次调用照样会写 `chat_tool_call` 记账——审计看到的是「模型确实调了」，而不是「被拦掉了」。
2. **提示层**（首选）：`get_session_state` 会把「已调用过的工具 + 结论摘要」交给模型，并在 `hint` 里明确要求「已调用过的直接复用，别重复调」；各工具返回体也带 `hint` 说明还能拿到什么。让模型一开始就别调第二次，比事后拦更好。

**这不是限流**：调用次数上限仍由框架的 `spring.ai.tools.limits.max-total-tool-calls`（当前 8）负责，超了按 `on-limit-exceeded: throw` 抛错。去重只解决「同样的输入重复问」。

`AiToolContext` 的取值纪律：

```java
public static Long requireUserId(ToolContext ctx) {
    Long id = optionalLong(ctx, USER_ID);
    if (id == null) { throw new IllegalStateException("ToolContext 缺少 userId"); }  // 代码缺陷必须炸
    return id;
}
```

为什么这里反而要炸：如果缺 userId 时降级成「查默认用户」，模型可以被诱导读到别人的血糖。**越权风险面前，宁可 500 也不能猜。**

---

## 9. 模型运行时：一次调用如何变成具体的 HTTP 请求

### 9.0 `config/AiDynamicModelConfig.java`：两个委托 Bean

```java
@Bean @Primary
@ConditionalOnExpression("'${spring.ai.model.chat:}' != 'none'")
public ChatModel glucopilotTextChatModel(AiRuntimeConfigService runtimeConfig,
                                         ChatModelFactory chatModelFactory,
                                         ObjectProvider<ChatModel> allChatModels) {
    // 协议统一后容器里只有这一个 ChatModel（DeepSeek starter 已移除），
    // 没有"别人的模型"可以复用，所以 yaml 兜底恒为 null、yaml 规格每次都现造。
    Supplier<ChatModel> yamlFallback = () -> null;
    return new DynamicChatModel("text", runtimeConfig, chatModelFactory,
            AiRuntimeConfigService.Snapshot::resolveTextDefault,   // 通道默认规格
            AiRuntimeConfigService.Snapshot::yamlText,             // yaml 规格（用于判断是否需要新建实例）
            yamlFallback);
}

@Bean
@ConditionalOnProperty(prefix = "ai.vision", name = "enabled", havingValue = "true")
public ChatModel glucopilotVisionChatModel(...) {            // 非 @Primary，由 AiVisionClientConfig 按名字注入
    return new DynamicChatModel("vision", runtimeConfig, chatModelFactory,
            AiRuntimeConfigService.Snapshot::resolveVision,
            AiRuntimeConfigService.Snapshot::yamlVision,
            null);                                            // 视觉没有 yaml 兜底实例可复用
}
```

两个注释里的坑值得记住：

- **协议统一（2026-11）之后不再有第二个 `ChatModel` Bean**。`spring-ai-starter-model-deepseek` 已移除，`ChatClientAutoConfiguration` 的单值 `ChatModel` 参数只会拿到本委托；`yamlFallback` 恒为 `null`，yaml 规格一律由 `ChatModelFactory` 现造（重试与观测改由 `OpenAiChatOptions.maxRetries` 与 `ObservationRegistry` 承担）。
- **通道默认不能写成 `resolveText(null)` 或 `resolveText(yamlText().modelCode())`**。前者直接返回 yaml 规格，后者会永远盯着 yaml 里那个模型名——后台把它停用、改启用另一个模型后依然解析回旧模型（2026-10-13 的线上反馈）。所以这里传的是 `Snapshot::resolveTextDefault`。


### 9.1 `DynamicChatModel`（196 行）：名字 → 实例

```java
protected ChatOptions getOptions() {   // 只覆写这一个（Spring AI 2.0 契约；旧 getDefaultOptions 已删）
    return chatClientRegistry...;       // 实际由 optionsBuilder 按场景给
}

// 真正的解析发生在每次 call/stream
public ChatResponse call(Prompt prompt) {
    Snapshot snapshot = runtime.snapshot();          // TTL 到期就重载，否则复用一个不可变快照
    pruneIfConfigChanged(snapshot);                  // 按内容指纹清理不再需要的实例
    AiModelSpec spec = resolveSpec(prompt, snapshot);
    ChatModel model = modelFor(spec);
    return model.call(prompt);
}
```

`resolveSpec`：`prompt.getOptions().getModel()`（调用方显式点名）→ `snapshot.resolveByName(name)` 命中即用，否则用 `channelDefault`（由装配处传进来的「通道默认规格」）。

`modelFor(spec)` 有一个容易忽略的优化：

```java
if ("yaml".equals(spec.source()) || equivalentToYaml(spec)) {
    return yamlFallback.get();     // 复用自动装配的实例：保留 RetryTemplate、Micrometer 观测、客户端连接池
}
return models.computeIfAbsent(cacheKey(spec), factory.build);   // cacheKey = modelCode + "|" + fingerprint
```

如果配置和 yaml 一致却新建实例，会丢掉重试与观测——而且每次请求新建 HTTP 客户端。`pruneIfConfigChanged` 的判据是**内容指纹**而不是时间：TTL 到期但配置没变，不重建任何东西。

### 9.2 `AiRuntimeConfigServiceImpl`（413 行）：快照 + 默认模型判定

```java
public boolean isStale() { return System.nanoTime() - loadedAtNanos > REFRESH_TTL; }

Snapshot load() {
    // DB 行 → toSpec → byCode / byName（视觉 yaml 也进索引，否则按编码引用对视觉通道空转）
    // yaml 侧 putIfAbsent（DB 优先）
    // 候选按用途分流：pickDefault(TEXT) / pickDefault(VISION)
    // 每类判据：DEFAULT_FLAG → ONLY_ENABLED → RECENT(带 WARN) → YAML
}
```

- **`capability` 决定候选池**（V48，替代 V45 的 `model_type`）：含 `vision` 的模型只参与视觉默认判定、其余只参与文本默认判定，互不抢占；历史行 `NULL` 视为 `text`。默认标记也是按池各一个（`clearOtherDefaults` 带池条件）。路由链上的「这个模型能不能干这件事」用 `AiModelCapability.matches(required)` 判定（模型能力必须是场景要求的超集）。
- **版本号 = 内容指纹**，不是时间戳：`setVersion(hash)`。TTL 到期重载但配置没变 → 版本不变 → 上层不重建 model。
- **`catch (RuntimeException)` 保留旧快照**：DB 挂了就用上一份；首次就失败则退化成「纯 yaml」。
- **`textSpec(scene)` 只做「按用途取默认」**（C2 起）：不再读 yaml 的 `ai.scenes.<scene>.model-code`——那个键曾经排在「后台默认模型」之上，导致运营在后台把默认模型换成 B 之后，场景仍然按 yaml 写的 A 去调（「记录写 A、实际调 B」的典型来源）。现在值来源只有一处：`ai_scene_config.model_code`（见 `SceneModelResolver.modelCode`）。老配置里残留的该键会在启动时被 `warnAboutDeprecatedSceneModelCode()` 点成 WARN。

### 9.3 `SceneModelResolver`：场景 → 模型 + 参数

```java
public AiModelSpec modelCode(SceneCode scene) {
    SceneBinding binding = sceneBindings.binding(scene);          // 永不 null
    if (scene.isRequiresVision()) {
        if (binding.hasModel()) {                                  // 视觉绑定也要生效
            AiModelSpec bound = runtimeConfig.specByModelCode(binding.modelCode());
            if (bound != null && bound.vision()) { return bound.modelCode(); }
        }
        return runtimeConfig.visionSpec().modelCode();             // 视觉绝不回落文本模型
    }
    if (binding.hasModel()) {
        AiModelSpec bound = runtimeConfig.specByModelCode(binding.modelCode());
        if (bound != null && bound.capability().contains(AiModelCapability.TEXT)) { return bound.modelCode(); }
    }
    return runtimeConfig.textSpec(scene).modelCode();              // 未绑定 → 用途默认
}

public ChatOptions.Builder<?> optionsFor(SceneCode scene) {
    // 协议统一后只剩一条路径：视觉/文本都产出 OpenAI 兼容参数
    return openAiOptions(scene, resolveModelSpec(scene));
}
```

| 方法 | 场景 | 参数来源 |
| --- | --- | --- |
| `optionsFor` | 文本 / 视觉 | 绑定 > `ai.scenes.<scene>.*`；model 恒写 `spec.modelEndpoint()`（yaml 只给参数，不给模型） |
| `visionOptionsBuilder` | 视觉场景 | 绑定（含 model 端点）> `ai.scenes.<scene>.*`；三者全空返回 null（继承模型默认） |
| `memoryOptionsBuilder` | MEMORY | `disableThinking()` + `ai.memory.memory-extract-max-tokens` |
| `summaryOptionsBuilder` | SUMMARY | `disableThinking()` + `ai.memory.summary-max-tokens` |

记忆抽取/摘要都 `disableThinking()`：推理模型的思考段会把读取超时顶爆，且这一步不需要推理性。

### 9.4 `AiChatClientRegistry`：Bean 缺失的友好降级

```java
public ChatClient textClient() {
    ChatClient c = textClients.getIfAvailable();
    if (c == null) { throw new BizException(ErrorCode.BIZ_REJECTED, "文本 AI 未启用，请联系管理员配置模型服务"); }
    return c;
}

public ChatClient visionClient(SceneCode scene) {
    if (!scene.isRequiresVision()) { throw new IllegalArgumentException(scene + " 不是视觉场景"); }
    ChatClient c = visionClients.getIfAvailable();
    if (c == null) { throw new BizException(ErrorCode.BIZ_REJECTED, "该能力需要多模态模型，当前尚未接入"); }
    return c;   // 明确不回落文本模型：拿文本模型喂图片只会得到一段胡说
}
```

用 `ObjectProvider<ChatClient>` 而不是直接注入：视觉没配时应用仍能启动（否则所有 C 端接口一起挂）。

### 9.5 `ChatModelFactory`（约 180 行）与 `AiModelCipher`（148 行）

```java
public ChatModel build(AiModelSpec spec)                     // 只有一条路径：OpenAI 兼容
private ChatModel buildOpenAiCompatible(AiModelSpec spec)    // DeepSeek 官方端点、百炼、任意 OpenAI 兼容网关
public ConnectivityResult test(AiModelSpec spec, Duration timeout)   // 管理端「测试连通性」
static String classify(Throwable e)               // 401/403/404/超时/DNS → 可读分类
public record ConnectivityResult(boolean ok, String category, long elapsedMs, ...) { public String advice() }
```

`classify` + `advice()` 是给运维用的：把「连不上」翻译成「密钥无效/路径不存在/网络不通」，而不是抛一个 stack trace 给管理端页面。

**协议统一（2026-11，已完成）**：DeepSeek 官方 API 本身就是 OpenAI 兼容的，原先却用 Spring AI 的 DeepSeek 专用实现，
后果是 `timeout / maxRetries / proxy` 三个参数根本没地方传（`DeepSeekChatOptions` 里没有它们，只有 `DeepSeekApi.Builder` 的 `restClientBuilder` 能间接设超时）。
现在**只剩一条构造路径** `buildOpenAiCompatible`：DeepSeek 专用分支与 `AiProtocol` / `AiProtocolResolver` / `AiHttpTimeouts` 已全部删除。

- 参数收敛到 `OpenAiChatOptions`：`timeout` / `maxRetries(3)` / `proxy` 都有落点；该 Builder 没有 `retryTemplate`，重试靠 `maxRetries`。
- thinking 用 `extraBody({"thinking":{"type":"..."}})` 下发（OpenAI 规范无此字段，DeepSeek 接受为私有扩展）。
- `replayReasoningContent(true)` 保证多轮思考连贯性。
- **坑**：`OpenAiChatOptions.builder()` 会预填 SDK 默认模型名，`DynamicChatModel` 会把它当成「调用方指定了模型」原样发给厂商
  （实测发到 DeepSeek 端点报 400：`but you passed gpt-5-mini`）。所以只要构造了 OpenAI options，就必须显式 `builder.model(spec.modelEndpoint())`。
- 依赖：`spring-ai-starter-model-deepseek` 已移除，显式引 `spring-ai-openai` / `spring-ai-client-chat` /
  `spring-ai-autoconfigure-model-tool`（后两者原先由 starter 传递；少了它们编译期整包找不到，运行期也拿不到 `ToolCallingManager`）。
- 回滚：本步没有开关，只能 `git revert` 掉 S3 那个提交。

```java
// AiModelCipher：模型 API Key 落库前加密
public static final String ALGO_AES_GCM = "AES-GCM";
private static final int GCM_IV_BYTES = 12, GCM_TAG_BITS = 128, CURRENT_KEY_VERSION = 1;
public AiModelCipher(@Value("${AI_CONFIG_MASTER_KEY:}") String masterKey)
public boolean available()                       // 未配置主密钥 → false，上层报「请配置 AI_CONFIG_MASTER_KEY」
public String encrypt(String plainText)
public String decryptOrNull(String cipherText)   // 解不开返回 null，不抛
public static String mask(String secret)         // 列表页展示 sk-****abcd
public int currentKeyVersion()                   // 密文带版本号，支持主密钥轮换
```

### 9.6 `AiRuntimeStatusServiceImpl`：把「为什么是这个模型」做成接口

C2 之前，这个问题只能靠翻启动日志（而且快照日志只在**首次加载**时打一次）或读代码推断。现在：

```java
GET /admin/v1/ai/runtime/status        // 权限 ai:model:query，只读
public AiRuntimeStatusDtos.Status status();   // 快照 + 场景绑定 → 现状 + issues
```

每个场景的 `source` 只有三种取值，正好对应代码里的三条链：

| source | 含义 | 代码里的位置 |
| --- | --- | --- |
| `binding` | 后台 `ai_scene_config.model_code` 生效（能解析且能力匹配） | `SceneModelResolver.modelCode` 的绑定分支 |
| `default` | 没绑定或绑定不可用，走通道默认（DB 的默认标记/唯一启用者） | `Snapshot.resolveTextDefault` / `resolveVisionDefault` |
| `fallback` | DB 里连默认都没有，落 yaml 通道兜底 | `AiModelSpec.source() == "yaml"` |

`issues` 收集的是「大概率不符合预期」但不报错的配置：绑定指向不存在的模型、视觉场景绑了文本模型、某通道没有 DB 默认、有模型行因密钥解不开被跳过。

实现上刻意**不复用** `SceneModelResolver.modelCode`：那个方法每次判定失败都会 WARN，而诊断接口会被后台反复刷新；这边只做同样的判定、不打日志。代价是判定条件写了两份，所以 `AiRuntimeStatusServiceTest` 里有专门的用例守「绑定不可用时必须如实标成 default 并给出 issue」——否则这个接口就会开始说谎。

### 9.7 保存前连通性校验：把「配错了」提前到保存那一刻

P1 之前，后台保存模型配置只做**格式**校验：编码非空、能力合法、编码不重复。至于「这个 endpoint 厂商认不认」「这把 key 属于这个账号吗」「baseUrl 少没少 `/v1`」，都要等到管理员自己点「测试连通性」，或者更糟——等线上第一条用户提问失败。

现在 `AdminAiModelServiceImpl.verifyBeforeSave` 在**新增/编辑保存**时真发一次探针：

| 模型能力 | 探针 | 为什么 |
| --- | --- | --- |
| 文本（不含 `vision`） | `new Prompt("ping")` | 最便宜的一次真实补全 |
| 视觉（含 `vision`） | 1×1 透明 PNG + 「这张图是什么颜色？只回答一个词。」 | 视觉模型只吃文本 prompt 会被厂商拒，导致**配置正确反而测不过** |

三种放行方式都会留痕（`ai_model_config.verify_skipped_reason`，V51）：

1. 校验通过 → 列清空；
2. 管理员勾选「跳过」并填原因 → 原因落库，列表里那一行显示「未校验」+ 悬浮原因；
3. 服务端整体关闭（`ai.model.verify-on-save=false`）→ 落一句固定说明。

失败时抛 `BizException(BAD_REQUEST, "连通性校验未通过：" + advice + "（" + category + "：" + message + "）")`：`category` 来自 `ChatModelFactory.classify`（`AUTH` / `MODEL_NOT_FOUND` / `RATE_LIMIT` / `TIMEOUT` / `NETWORK` / `UNKNOWN`），`advice` 是给管理员看的「该改哪个字段」。**没通过就不落库**——否则后台会攒出一堆"看起来正常、一用就 401"的行。

两个实现细节：探针在事务内执行，最长占用一个连接 30s（保存是低频人工操作，这个代价换"填错了存不进去"是划算的）；`update` 时探针用的凭据是「新密钥 > 库里旧密文 > 清空即无」，不能用 yaml 的 key 去代表这一行——否则会出现"测着成功、线上 401"。

### 9.8 用量出口：`AiUsageServiceImpl`（天 × 模型）

`chat_message` 一直有 `model_code / prompt_tokens / completion_tokens / latency_ms`，但**全仓没有任何聚合**——运营只能看到"模型配了几条"，看不到"花了多少"。`GET /admin/v1/ai/usage`（接口文档 4.28）补上这个出口。

三个刻意的选择：

1. **单开 `AiUsageMapper`**，不塞进 `ChatMessageMapper`：后者是对话写入路径的热点，且用量查询带 `GROUP BY`，语义与"按会话取消息"完全不同。
2. **只统计 `msg_role = 2`**（助手消息）：用户消息不带 token，算进去会把"调用次数"直接翻倍。
3. **半开区间 `[start 00:00, end+1d 00:00)`**：避免 MySQL `DATE_ADD` 与 H2 `DATEADD` 的方言差异，也不会漏掉结束日带毫秒的行。

维度只能是「天 × 模型」：摘要、记忆抽取、内容审核、饮食识别、OCR 这些内部调用都只取 `.content()`，没有用量记录。所以接口返回一个固定 `note`，前端原样展示——让所有人看到同一句口径说明，而不是各自写一句不同的解释。

### 9.9 记忆抽取的结构化输出试点

记忆抽取原本靠"模型自由输出 JSON + 手写宽松解析"。改成 `call().entity(MemoryPayload.class)` 之后：

- `BeanOutputConverter` 生成 JSON Schema，模型按 schema 输出；
- `StructuredOutputValidationAdvisor` 负责把格式说明追加到用户消息、并在返回后按 schema 校验（`maxRepeatAttempts=1` 时失败重试一次）。

**最容易漏的一点**：只调 `entity()` 是不够的。2.0.2 的 `DefaultCallResponseSpec.entity()` 只把格式说明写进 `ChatClientAttributes.OUTPUT_FORMAT`（`javap` 确认，没有任何 `augmentUserMessage` 调用），真正消费它的是 `StructuredOutputValidationAdvisor`（`Prompt.augmentUserMessage`）。不挂这个 advisor，模型收到的还是"随便输出 JSON"的原始提示词，schema 只是摆设。

开关 `ai.memory.structured-output`：代码默认 `false`（单测里 mock 的客户端只桩了 `content()`），`application.yaml` 里打开。yaml 里必须写裸 `true`，不能写 `${ENV:true}`——`AiPropertiesBindingTest` 会直接解析 yaml 做绑定，未解析的占位符会让 `boolean` 绑定失败。

两条路径的候选都走同一个 `dedupeByKey()`：否则"同 key 取置信度更高者"这条规则会在两处各写一遍，早晚改漏一处。


---

## 10. 记忆与摘要

### 10.1 `UserChatMemoryServiceImpl`（427 行）：抽取长期记忆

```java
public void extractAsync(Long userId, Long sessionId, String reason) {
    String key = "chat-memory-extract:" + sessionId;
    if (!extractGuard.tryAcquire(key)) { return; }      // 单飞：同一会话不并发抽两次
    executor.execute(() -> {
        try { runExtract(userId, sessionId); }
        finally { extractGuard.release(key); }
    });
}
```

`runExtract` 的输入构造是重点：

```java
List<ChatMessage> recent = chatMessageMapper.selectRecentBySession(sid, ai.memory.memory-extract-max-messages);
String input = buildInput(recent);
// ① 先把「已知的长期记忆」放前面，让模型可以更正而不是重复新增
// ② 再按 memory-extract-input-max-chars 从最新往回装对话原文
```

调用与解析：

```java
String raw = memoryClient.prompt()
        .system(promptTemplateService.systemPrompt(SceneCode.MEMORY))
        .options(sceneModelResolver.memoryOptionsBuilder())
        .user(input).call().content();
parse(raw).forEach(this::upsert);      // 剥 ```json 围栏后宽松解析 memories[]，同 key 取置信度高者
```

对外三个方法：`forInjection(userId)`（按置信度取前 N 条注入系统提示词）、`listOwnForUser`、`deleteAllForUser`（用户自助清空）。`FORBIDDEN_MARKERS` 是一层兜底：即使模型抽出了像密钥/身份证的内容，也不入库。

### 10.2 `ChatSummaryServiceImpl`（298 行）：滚动摘要

```java
public void maybeSummarizeAsync(Long userId, Long sessionId) {
    if (!overThreshold(sessionId)) { return; }          // 没超过保留条数就不做
    String key = SUMMARY_KEY_PREFIX + sessionId;
    if (!summaryGuard.tryAcquire(key)) { return; }      // 单飞
    executor.execute(this::runSummarize);
}
```

`runSummarize` 最巧的一步是**用 `upto` 做乐观并发**：

```java
ChatMessage upto = 候选里的最后一条;
int updated = chatSessionMapper.updateSummary(sessionId, limited, upto.getId());
if (updated == 0) { /* 指针已经前进了（别的实例先完成），丢弃本次结果 */ }
```

摘要只往「已摘要到哪条」的指针后面推进，`update ... where context_upto < ?` 保证不会用旧摘要覆盖新摘要——这是多实例部署下唯一安全的写法（进程内单飞管不到另一个实例）。

### 10.3 `ChatRoundGuard`（92 行）：进程内单飞

```java
static final Duration STALE_AFTER = Duration.ofMinutes(5);
private final Map<String, Long> inFlight = new ConcurrentHashMap<>();

public boolean tryAcquire(String key) {   // 顺便清理超过 5 分钟的僵尸条目
    long now = System.currentTimeMillis();
    return inFlight.compute(key, (k, startedAt) ->
            (startedAt == null || now - startedAt > STALE_AFTER.toMillis()) ? now : startedAt) == now;
}
```

**注意它的边界**：这是 `ConcurrentHashMap`，只在单进程内有效。多实例部署时同一轮仍可能被两个实例同时生成——真正的一致性由 DB 兜底：`selectAssistantReply` 幂等 + `chat_message` 唯一键。摘要靠 `upto` 指针，审核靠 `FOR UPDATE SKIP LOCKED`。

### 10.4 `MyBatisChatMemoryRepository`（164 行）：把 Spring AI 的 `ChatMemory` 接到自家表

```java
public class MyBatisChatMemoryRepository implements ChatMemoryRepository {
    public List<String> findConversationIds();
    public List<Message> findByConversationId(String conversationId);   // conversationId = sessionId 的字符串
    public void saveAll(String conversationId, List<Message> messages);
    public void deleteByConversationId(String conversationId);
    private static String replayText(ChatMessage row);   // 有正文用正文；否则 "[用户发来的图片，内容摘要] " + mediaCaption
}
```

实现 `ChatMemoryRepository` 而不是自己写一套读取，好处是 `MessageWindowChatMemory`（裁剪）与框架的记忆语义全都白拿；代价是要处理「自家表比 Spring AI 的 `Message` 多很多字段」（tool_calls、media_id、caption）的映射。

---

## 11. 多模态：`VisionAnalyzer` → 三个业务场景

```java
public String analyze(MediaFile media, SceneCode scene, String instruction) {
    ChatClient visionClient = chatClientRegistry.visionClient(scene);   // 视觉场景断言 + 未接入报错
    String mimeType = requireImage(media, aiProperties.getVision());    // 复用 file 模块的 MIME 白名单
    byte[] bytes = readWithinLimit(media, vision);                      // ai.vision.max-bytes 超限直接拒
    ChatClient.ChatClientRequestSpec prompt = visionClient.prompt()
            .system(promptTemplateService.systemPrompt(scene));          // 每请求现取（后台可改）
    ChatOptions.Builder<?> opts = chatClientRegistry.visionOptionsBuilder(scene);
    if (opts != null) { prompt.options(opts); }
    return prompt.user(u -> u.text(instruction)
                    .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(bytes)))
            .call().content();
}
```

三个刻意的决定：

- **MIME 白名单复用 file 模块的 `MediaFileConstant.isMimeAllowed`**，不在 AI 模块再维护一份。两份清单迟早不一致，症状是「前端传得上去、模型读不了」。
- **`ai.vision.max-bytes` 在读取前校验**，不是读进内存再看长度。
- **`instruction` 由调用方给**：同一个视觉模型，饮食分析要「列食物和克数」，OCR 要「抄下屏幕上的数字和单位」——提示词不同，链路相同。

三个调用方：

| 调用方 | 场景 | 流程 |
| --- | --- | --- |
| `ChatCompletionServiceImpl.imageTurn` | `CHAT_IMAGE` | 先 `describe(media, userText)` 出描述 → 回写 caption → 阶段 2 把描述当用户消息送文本模型 |
| `MealAnalysisServiceImpl.analyze` | `MEAL_ANALYSIS` | `vision.analyze(...)` → `parse(raw)` → `catalog.resolve(food, model, mediaId)` 匹配食物库 → 汇总热量/营养 → `idempotency.saveIfAbsent(key, result)` |
| `OcrTaskServiceImpl` | `OCR_GLUCOSE` | 建任务（PENDING）→ 线程池跑 `vision.analyze(media, OCR_GLUCOSE, RECOGNIZE_INSTRUCTION)` → 成功存 JSON、失败存可读错误 → `detail` 轮询 |

`MealAnalysisServiceImpl` 的 `MealFoodCatalogService.resolve` 返回 `record Resolution(MealAnalysisDtos.Item item, boolean added)`——`added=true` 表示这条食物是**新入库**的（模型认得、库里没有），便于后续人工校对。

---

## 12. 语音：`SpeechTranscriptionService`（不走 ChatClient）

```java
public SpeechDtos.Result transcribe(Long userId, SpeechDtos.Request request) {
    validateAudio(request);      // 只收 60 秒内的 MP3
    // RestClient 直连百炼 ASR（qwen3-asr-flash）
    // 401 → "语音识别尚未配置，请配置百炼语音密钥后重试"
    // 429 → "语音识别繁忙，请稍后重试"
    // 其它 → "语音识别调用失败，请检查语音模型和API配置后重试"
}
```

为什么不复用 ChatClient：ASR 是**文件上传 + 转录**协议（multipart），不是 chat completions。硬套只会得到一堆适配代码。这里用 `RestClient` + `SpeechProperties`（`ai.speech.*`），密钥未配时就抛「尚未配置」——而不是静默返回空串。

---

## 13. 社区内容审核（AI 侧三个类 + 一个解析器）

设计文档在 `docs/AI-社区内容审核设计.md`，这里只讲实现骨架。

### 13.1 `CommunityModerationWorker`：只剩节拍

收束后它不再碰提示词、模型与解析——**只负责「多久捞一次」与「捞到之后交给谁」**：

```java
@Scheduled(initialDelayString = "${community.moderation.initial-delay:10000}",
           fixedDelayString  = "${community.moderation.delay:5000}")
public void runOnce() {
    CommunityModerationQueue.Job job = queue.claim();          // 文章优先，其次评论
    if (job == null) { return; }
    ModerationResult result;
    try {
        result = moderation.judge(request(job));               // ai/business/moderation
    } catch (RuntimeException e) {
        // 能力层承诺不抛异常，这里只是最后一道保险：失败不放行
        result = new ModerationResult(ModerationVerdict.error("AI 初审暂不可用，请重新初审；未自动发布文章"), "", "");
    }
    queue.finish(job, result.verdict(), result.modelCode(), result.promptVersion());
}
```

判断逻辑在 `ModelContentModerationService`：`clients.utilityClient()` → `prompts.systemPrompt(MODERATION)` → 45s 超时 → `ContentModerationParser.parse` → 失败转 `ERROR`。**`community` 侧不再 import 任何模型类**，这条单向依赖由 `AiModuleBoundaryTest` 守着。

`ModerationRequest.promptText()` 用**纯文本字段清单**而不是 JSON：

```
类型：文章
标题：…
标签：糖尿病、饮食
正文：
…
```

理由同 §7.2：提示词里不能出现成对花括号，让模型照抄 JSON 等于自找解析失败。

`modelCode` 取 `response.getMetadata().getModel()`——**落库的必须是真被调用的那个模型**，而不是「按配置应该是的那个」。两者不一致时，排查只能靠这一列。

`modelCode` 取 `response.getMetadata().getModel()`——**落库的必须是真被调用的那个模型**，而不是「按配置应该是的那个」。两者不一致时，排查只能靠这一列。

### 13.2 `CommunityModerationQueue`：SKIP LOCKED + 僵尸回收

```java
public Job claim() { Job a = claimArticle(); return a != null ? a : claimComment(); }
```

`claimArticle`：

```sql
-- ① 回收上次中断的（进程被杀会永远停在 AI_REVIEWING）
UPDATE community_article SET state='AI_ERROR', ai_verdict='ERROR', ai_reason='上次初审中断，请重新初审'
 WHERE state='AI_REVIEWING' AND updated_at < NOW(3) - INTERVAL 3 MINUTE;

-- ② 抢一条
SELECT a.news_id, a.revision, n.title, n.content_body ...
  FROM community_article a JOIN cms_news n ON n.id = a.news_id
 WHERE a.state='AI_PENDING' AND n.community_managed=1 AND n.is_deleted=0
 ORDER BY a.news_id LIMIT 1 FOR UPDATE SKIP LOCKED;
```

`claimComment` 的回收方向不同：`REVIEWING` 过 3 分钟**退回 `PENDING`**（评论没有人工「重新初审」按钮，判错就永久卡住）。文章有管理端按钮，所以回收成 `AI_ERROR` 让人看得见。

`finish` 用**状态 + 版本条件更新**兜底并发：

```java
// 文章
UPDATE ... SET state = (PASS→PENDING_ADMIN / REJECT→AI_REJECTED / 其余→AI_ERROR)
 WHERE news_id=? AND revision=? AND state='AI_REVIEWING';
// 评论
UPDATE ... SET ai_status='DONE', is_hidden = ?          -- REJECT→1，PASS/ERROR→0
 WHERE id=? AND ai_status='REVIEWING';
```

`changed != 1` 返回 false 而不是抛错：并发/版本已变是**正常竞争**，不是故障。成功后才 `insertHits(...)` 与 `publication.log(...)`。

> 注意文章的 PASS 只是 `PENDING_ADMIN`：AI 初审通过仍需人工终审（先审后发）。评论是 PASS 直接可见、REJECT 直接 `is_hidden=1`（先发后审）。

### 13.3 审核能力的六个件（`ai/business/moderation`）

包内按职责分四层，和其它业务域同一套规则：`dto/`（跨接口的数据）、`support/`（无状态辅助件）、`service/`（接口）、`service/impl/`（实现）。

| 类 | 所在子包 | 职责 |
| --- | --- | --- |
| `ContentModerationService`（接口） | `service/` | `ModerationResult judge(ModerationRequest)` + `String promptVersion()`；**承诺不抛异常**，失败返回 `ERROR` |
| `ModelContentModerationService`（实现） | `service/impl/` | `utilityChatClient` + `ai.scenes.moderation` 参数 + 45s 超时 + 解析；`community` 侧只依赖接口 |
| `ModerationRequest` / `ModerationResult` | `dto/` | 入参 `record(kind, title, tags, body)`（含 `promptText()` 摊平）/ 出参 `record(verdict, modelCode, promptVersion)` |
| `ModerationVerdict` | `dto/` | `record(verdict, category, severity, confidence, reason, hits)` + `PASS/REJECT/ERROR` 三态 |
| `ContentModerationParser` | `support/` | 逐行 `key：value` 解析；容忍中英文冒号/`=`/Markdown；`MAX_RESPONSE_CHARS=6000`、`MAX_REASON_CHARS=200`；**所有异常路径返回 `ERROR`，不返回 PASS** |
| `ModerationCategories` | `support/` | 12 个类别编码（`porn`/`violence`/`illegal_goods`/`medical_claim`/`medical_advice`/`scam_ad`/`privacy`/`hate_harass`/`political`/`minor`/`pseudo_science`/`off_topic`），`of(code)` 大小写不敏感，未知 → `NONE` |

**fail closed** 是这里的铁律：解析不出一行可识别的结论 → `ERROR`，而 `ERROR` 既不等于 PASS 也不隐藏内容——它把决定权交回人工。审核链最怕的不是漏判，是「解析失败被当成通过」。

---

### 13.4 中英翻译：`ai/business/translation`

`localization` 模块原来自己拼 `OpenAiChatOptions`、自己写 `SYSTEM` 常量、自己取 `ChatModel`。收束后职责切成两半：

| 谁 | 管什么 |
| --- | --- |
| `localization/LocalizationService` | 缓存表 `content_localization`、`(kind, resource_id, source_hash, locale)` 主键、PENDING 租约与 `retry_after`、批量上限、授权闸（`community.requireReadable`）、`direct()` 纯英文直判 |
| `ai/business/translation/service/EnglishTranslator`（接口） | **文本进、文本出**：`translate(kind, source)` + `promptVersion()`。它不知道文章、也不知道缓存表 |
| `ai/business/translation/service/impl/ModelEnglishTranslator` | `utilityChatClient` + `ai.scenes.translation`（`json: true`）+ 校验（数字/单位/URL 保真）+ 重试 1 次 |

两个细节值得记：

- **提示词版本进缓存键**：`source_hash = SHA-256(CACHE_VERSION + promptVersion() + 源文本)`。改一版提示词就等于全量失效重翻，否则旧译文会永远留在库里；`content_localization.prompt_ver`（V54 新增）是为了事后能回答「这条译文是哪版提示词产的」。
- **必须 `.messages(...)` 不能 `.user(...)`**：`ChatClient.user(String)` 会把入参当模板渲染，而翻译的入参是 `{"title":"…"}` 这样的 JSON——花括号一进来就被 StringTemplate 当变量表达式。`SystemMessage` + `UserMessage` 是唯一安全写法，审核链路同理。

---

## 14. 后台管理面：三套「表 + 服务 + 页面」

| 关注点 | 表 | Service | Controller | 谁在读 |
| --- | --- | --- | --- | --- |
| 模型目录 | `ai_model_config` | `AiModelCatalogServiceImpl` + `AiRuntimeConfigServiceImpl` | `AdminAiModelController` | `DynamicChatModel` / `AiRuntimeConfigService.snapshot()` |
| 提示词 | `ai_prompt_template` | `AiPromptTemplateServiceImpl` | `AdminAiPromptController` | `ChatCompletionServiceImpl`（请求级注入） / 各业务 Service |
| 场景绑定 | `ai_scene_config` | `AiSceneBindingServiceImpl` | `AdminAiSceneController` | `SceneModelResolver` |

生效路径很短，这是刻意的：

```
管理端点保存 → 更新表 → refresh 服务内存快照 → 下一次请求直接生效（不重启、不发事件）
```

- 模型：`AiRuntimeConfigService` 有 `REFRESH_TTL`，到期后 `snapshot()` 重载；版本按内容指纹算，没变就不重建 HTTP 客户端。
- 提示词：`refreshNow()` 立即重载库表快照。
- 场景：绑定查询本身很轻，直接查。
- API Key：落库前 `AiModelCipher.encrypt`，读出时 `decryptOrNull`，列表页 `mask`。

---

## 14.1 会话审计面：只读两段式 + 读正文留痕

后台看用户对话，本质是「运维在读别人的隐私」，所以这一面刻意做成**两段式**，而不是一个万能查询接口。

| 关注点 | 表 | Service | Controller | 权限点 |
| --- | --- | --- | --- | --- |
| 会话清单 | `chat_session`（+ `user_profile` 昵称、`chat_context` 轮次计数） | `AdminChatSessionServiceImpl#list` | `AdminChatSessionController` | `ai:chat:audit` |
| 消息预览 | `chat_message` | `#messages` | 同上 | `ai:chat:audit` |
| 单条全文 | `chat_message` | `#messageDetail` | 同上 | `ai:chat:audit` |
| 分片账本 | `chat_context` | `ChatContextService` | `AdminChatContextController` | `ai:chat:audit` |

- **列表与预览都不返回正文**。预览 200 字封顶（`PREVIEW_MAX_CHARS`），先折叠空白再截断加 `…`，所以字符串最长 201 字符；图片取 `media_caption`、语音取 `asr_text`，都没有就按类型给 `[图片]`/`[语音]`/`[空消息]`。
- **全文接口是唯一出口**，且每次调用都写一条 `ops_audit_log`：`module_code=ai`、`action_code=ai_chat_audit_view`、`target_type=chat_message`、`target_id=消息ID`，`detail_json` 只放 `{"sessionId":N}` —— **正文不进审计**，审计只回答「谁在什么时候看了哪条」。
- **不提供按正文关键词搜索**：那等于隐式的全文批量读取，会绕过上面的留痕。会话列表的关键词只匹配标题与用户昵称。
- **越权一律 404 且不留痕**：`messageDetail` 先校验 `message.sessionId == sessionId`，不匹配按「消息不存在或不属于该会话」返回，避免「用会话 A 的入口试出会话 B 有没有消息 ID」。
- 权限点在 V53 从 `ai:model:query` 拆成 `ai:chat:audit`（含原始对话，只授超管），并顺带把用量页拆出 `ai:usage:view`：**配置、成本、隐私审计是三类不同的关注点**，不该共用一个权限点。
- 前端（管理端 `src/views/ai/ChatSessionView.vue`）只在用户点「展开原文」时请求全文，结果缓存在内存、收起即删；这是刻意的——预取会让审计表里堆满「没人真看过」的记录。
- **分片账本的唯一读出口是 `GET /admin/v1/ai/audit/sessions/{id}/context`，页面入口在同一个抽屉的「上下文构成」区块**：默认收起，展开才请求，用 `flex` 权重画一条按 `contextType` 着色的占比条（系统提示词 / 长期记忆 / 滚动摘要 / 工具摘要 / 历史消息），图例给出每个分片的估算 token。该区块**不写审计**——它只有元数据、没有正文，展开它不等于读了用户内容；这与「读正文必留痕」是两条独立的线。
- 这套分片**不参与任何运行时决策**：下一轮装配不读 `chat_context`（`ChatContextReader` 读的是摘要/工具摘要/长期记忆）、预算校准用厂商返回的真实 `prompt_tokens`、用量报表读 `chat_message`。它的价值只在「这一轮 2900 token 花在哪」的可解释性——这也解释了为什么页面做得很轻：没有它，问答照样正确、成本一分不差。

---

## 14.2 AI 审计面：七个页签各自读哪张表

会话审计只是「AI 审计」的一个页签。同一个页面（管理端 `/ai/audit` → `AiAuditView.vue`）把模型在业务里留下的**六种流水**聚在一起，全部只读、没有删除与导出：

| 页签 | 表 | Service / Mapper | Controller | 回答的问题 |
| --- | --- | --- | --- | --- |
| 会话 | `chat_session` / `chat_message` / `chat_context` | `AdminChatSessionServiceImpl` | `AdminChatSessionController` | 谁在用、聊了什么（见 14.1） |
| 工具调用 | `chat_tool_call` | `AiAuditServiceImpl#toolCalls` + `ChatToolCallMapper.selectAuditPage` | `AdminAiAuditController` | 模型调了哪些工具、成没成、花了多久 |
| 长期记忆 | `chat_user_memory` | `#memories` + `ChatUserMemoryMapper.selectAuditPage` | 同上 | 系统给对方记下了什么 |
| 滚动摘要 | `chat_session.context_summary`（**不是独立表**） | `#summaries` + `ChatSessionMapper.selectSummaryAuditPage` | 同上 | 摘要有没有把关键信息写丢 |
| 内容审核 | `community_moderation_hit` | `CommunityModerationMapper.selectHitAuditPage` → `CommunityModerationHitAuditQuery` | `AdminAiAuditController` | 内容被判成了什么 |
| 翻译 | `content_localization` | `LocalizationMapper.selectAuditPage` → `LocalizationAuditQueryImpl` | `AdminAiAuditController` | 文章/昵称翻成了什么、用的哪版提示词 |
| 多模态 | `ocr_task` | `#ocrTasks` + `OcrTaskMapper.selectAuditPage` | `AdminAiAuditController` | 图片识别成功没有、结果多长 |

三条边界，写代码时按这个走：

1. **路径统一到一个前缀，SQL 留在表主人那里**：十个审计端点全在 `/admin/v1/ai/audit/**`（会话四个在 `/sessions/**`，其余六个在根下），前端只认一套 URL。但 SQL 仍在 `community` / `localization` 里执行——`ai/audit/service` 只声明端口 `ModerationHitAuditQuery` / `TranslationAuditQuery`，两个模块各自提供实现（依赖倒置）。`AiModuleBoundaryTest` 明确禁止 ai 反向 import 这两个包，所以「把 SQL 搬进 ai」这条捷径不能用。
2. **权限统一挂 `ai:chat:audit`**：这七个页签是同一件事的不同侧面，各挂一个权限点只会让超管反复授权。代价是「能看审计的人也能看翻译缓存元数据」——因此翻译列表只给 200 字译文预览，不给全文。
3. **敏感内容两段式不变**：会话正文仍要「展开原文」才请求并留痕（14.1）；审核命中按 V43 的隐私设计**只存位置与片段哈希、不存原文**；记忆与摘要给全文是因为它们本就限长（≤200 字 / ≤800 字），截断会让审计答不出问题。

其它细节：

- **超长关键词一律 400，不静默截断**（`KEYWORD_MAX_CHARS=50`）：静默截断会让运营以为搜的是完整词，实际搜的是前缀。
- **不加时间区间过滤**：`chat_tool_call`、`chat_user_memory` 等表上没有「按时间范围」的复合索引，加了就是全表扫；需要定位具体时段时用主键游标（工具调用按 `id` 倒序）。
- **多模态只有 `ocr_task`**：餐食分析 `MealAnalysisServiceImpl.analyze()` 不落库（直接返回结果），所以「AI 看了哪张餐食图」没有可审计的记录——这是当前已知的覆盖缺口，不是页面遗漏。
- **菜单**：V55 把 V53 建的 2034 行改名为「AI 审计」并指向 `/ai/audit`（权限点不变，已有授权自动指向新页面），管理端路由与静态菜单同步替换掉原来的 `/ai/chat-sessions`。

---

## 15. 依赖关系一览

谁 depends on 谁（只列跨包的关键边）：

```
AiChatClientConfig ──► AiPromptTemplateService, ChatMemoryReadAdvisor, AiToolRegistry, AIProperties
ChatCompletionServiceImpl ──► AiChatClientRegistry, ChatMessageMapper, ChatSessionService, ChatMessageService,
                              OwnedMediaResolver, VisionAnalyzer, EnglishPreambleGuard, ChatRoundGuard,
                              AiPromptTemplateService, AiToolRegistry, TokenEstimator, ChatSummaryService,
                              UserChatMemoryService, ChatContextRecorder
ChatMemoryReadAdvisor ──► ChatMemory, ChatHistoryAssembler, ChatContextReader
ChatHistoryAssembler ──► TokenEstimator, ChatContextShard
ChatContextReader ──► ChatSessionMapper, ChatToolCallMapper, UserChatMemoryService
ChatCompletionServiceImpl ──► AiPromptTemplateService（请求级 .system 注入）
MedicalGuardAdvisor ──► AIProperties.GuardProperties
TracingToolCallback ──► ToolCallback, ToolCallTraceRecorder
ToolCallTraceRecorder ──► ChatToolCallMapper, ResultDigester, AiToolContext
DynamicChatModel ──► AiRuntimeConfigService, ChatModelFactory
AiRuntimeConfigServiceImpl ──► AiModelConfigMapper, AIProperties
SceneModelResolver ──► AIProperties, AiRuntimeConfigService, AiSceneBindingService
AiChatClientRegistry ──► ChatClient(ObjectProvider), SceneModelResolver
VisionAnalyzer ──► AiChatClientRegistry, AiPromptTemplateService, MediaFileConstant
MealAnalysisServiceImpl ──► MediaFileService, VisionAnalyzer, MealFoodCatalogService, JsonUtils, IdempotencyStore
OcrTaskServiceImpl ──► OcrTaskMapper, MediaFileService, VisionAnalyzer, JsonUtils, IdempotencyStore, Executor
CommunityModerationWorker ──► CommunityModerationQueue, ContentModerationService
ContentModerationService ──► AiChatClientRegistry, AiPromptTemplateService, SceneModelResolver, ContentModerationParser
ModelEnglishTranslator ──► AiChatClientRegistry, AiPromptTemplateService, SceneModelResolver, JsonUtils
LocalizationService ──► EnglishTranslator, LocalizationMapper
UserChatMemoryServiceImpl ──► utilityChatClient, ChatMessageMapper, ChatMemoryMapper, AiPromptTemplateService,
                              SceneModelResolver, ChatRoundGuard, Executor
ChatSummaryServiceImpl ──► utilityChatClient, ChatMessageMapper, ChatSessionMapper, ChatRoundGuard, Executor
```

三个「反向依赖」是刻意避免的：

- `core` 不依赖 `business`（否则场景解析要跟着业务变）。
- `ChatMemoryReadAdvisor` 不依赖 `ChatCompletionService`（Advisor 是被 ChatClient 调的，反向依赖会成环）。
- `VisionAnalyzer` 不认识饮食/OCR（业务语义留在业务 Service 里）。

**AI 模块也不依赖业务模块**：`ai/**` 里不允许出现 `community` / `localization` 的 import（`AiModuleBoundaryTest` 扫源码守着）。审核与翻译的「AI 侧」都在 `ai/business/{moderation,translation}`，业务侧只留表操作、状态机与节拍器。

---

## 16. 设计取舍与踩坑记录

| 决定 / 坑 | 现象 | 结论与理由 |
| --- | --- | --- |
| ChatClient 从注入的 Builder clone | 手搓 client 后工具静默失效 | 自动装配 Builder 上挂着 ToolCallingAdvisor 与观测，必须 clone |
| 提示词禁成对花括号 | 启动失败（V22/V23/V27） | ST 渲染语法；所有输出协议改逐行字段清单 |
| 不用 `SafeGuardAdvisor` | 全体请求被误拦 | 它扫含系统提示词的全文，而提示词本身写「开药/剂量」 |
| 守卫只扫最后一条 UserMessage | 曾误拦历史里的敏感词 | 用户新说的才是要拦的 |
| 幂等重放排在视觉识别前 | 重复请求多花一次视觉调用 | 慢且贵的外部调用必须在幂等之后 |
| 单飞在调模型前 | 并发两请求各花一次（实测 5855+5940 tokens） | 先抢锁再花钱 |
| `doFinally` + try/catch 双还锁 | 同步异常时锁泄漏 | 流未被订阅时 doFinally 不触发 |
| `selectNextSeqNo` 不过滤 is_deleted | 软删最大号后重试永远失败 | 重算必须必然前进 |
| 审核解析失败 → ERROR | 曾担心「误伤」 | fail closed：解析失败不能等于通过 |
| 审核 modelCode 取响应元数据 | 配置与实际不一致时无法排查 | 落库必须是真被调用的模型 |
| 评论 REVIEWING 回收成 PENDING | 评论没有人工重试按钮 | 文章回收成 AI_ERROR（管理端可见可重试） |
| 记忆/摘要读失败一律降级 | 增强项不该打挂主流程 | 返回空列表，不抛 |
| 工具缺 userId 直接抛 | 防止模型诱导查他人数据 | 越权面前宁可 500 |
| 视觉不回落文本模型 | 文本模型喂图片得到胡说 | 明确报「需要多模态模型」 |
| `disableThinking()` 于记忆/摘要 | 推理段顶爆读取超时 | 这两步不需要推理 |
| `ChatRoundGuard` 是进程内 Map | 多实例不互斥 | 一致性由 DB 唯一键/SKIP LOCKED/upto 指针兜底 |

---

## 17. 测试与验收

| 层次 | 数量/内容 |
| --- | --- |
| AI 相关测试类 | 59 个（`ai/**` 下） |
| 全量单测 | 985 用例，0 失败 0 错误 5 跳过 |
| 关键测试类 | `ChatCompletionServiceImplTest`、`ChatHistoryAssemblerTest`、`ChatMemoryReadAdvisorTest`、`AdvisorChainOrderTest`（已带 system 的请求喂给记忆顾问后的消息顺序）、`ChatCompletionServiceTest.发给模型的请求首条必须是当前场景提示词`、`MedicalGuardAdvisorTest`、`AiPromptTemplateServiceImplTest`、`DynamicChatModelOptionsTest`、`ChatControllerSseWireTest`、`MealAnalysisServiceImplTest`、`OcrTaskServiceImplTest`、`ContentModerationParserTest`、`ModelContentModerationServiceTest`、`CommunityModerationWorkerTest`、`CommunityCommentModerationTest`（H2 真实库）、`ModelEnglishTranslatorTest`、`LocalizationServiceTest`、`AiModuleBoundaryTest`（守卫 `ai` 不依赖业务包）、`ToolRepeatGuardTest`、`RepeatAwareToolCallbackTest`、`SecondBatchToolContractTest`（第二批 5 个工具的契约） |
| 端到端（真实模型） | `scripts/e2e/session-e2e.ps1` 36、`prompt-e2e.ps1` 45、`memory-e2e.ps1` 20、`moderation-e2e.ps1` 56、`default-model-e2e.ps1` 22、`ai-routing-e2e.ps1` 29、`chat-audit-e2e.ps1` 33、`translation-e2e.ps1` 14 |
| 迁移 | Flyway v60（V48 capability、V49 场景参数、V51 校验跳过原因、V53 审计与用量权限点、V54 翻译场景 + `content_localization.prompt_ver`、V55 审计菜单；V56~V60 为队友的社区/预测迁移） |

测试约定（新写测试要遵守）：

- 生产包路径镜像：`src/test/java/.../ai/xxx` 与主代码同包，便于访问包级方法（如 `withMemoryForTest`）。
- H2 用 `MODE=MySQL`，但**手写 schema 要跟着生产迁移走**：`CommunityTestSchema.applyModerationMigration` 是典型例子。
- 涉及 SSE 的断言用 `ChatControllerSseWireTest` 的方式校验原始字节，不要用 MockMvc 的字符串比较。

---

## 18. 已知边界（读代码时别误以为是 bug）

1. **`ChatRoundGuard` 是进程内的**：多实例部署下同一轮可能被两个实例生成，最终由 DB 幂等收敛（多花一次钱）。要根治需要 Redis 分布式锁。
2. **摘要异步只在进程内单飞**：多实例靠 `updateSummary ... where upto < ?` 的乐观并发，丢弃落后结果。
3. **记忆预算默认 4096 偏小**：长会话下保底条数会挤占摘要与记忆的位置。
4. **估算器需要 3~5 轮收敛**：前几轮的裁剪可能偏保守/偏激进。
5. **`chat_context` 只写不读**：分片目前是「可观测性」用途，还没有回读拼装（回读需要额外的一致性设计）。
6. **提示词禁成对花括号**：想输出 JSON 就得先在 `PromptTemplate` 层面解决逃逸问题。
7. **审核不覆盖图片/视频/私信**：配图与视频不送审，私信不在审核范围内。
8. **规则层/影子模式/自动重试/申诉/指标页未实现**：均在 `docs/AI-社区内容审核设计.md` §0 的待办清单里。

---

## 附：按「我要改什么」找文件

| 需求 | 从哪下手 |
| --- | --- |
| 改系统提示词 | `resources/prompts/<scene>/system.md` 或管理端 `ai_prompt_template`（改库不用重启） |
| 调温度/最大 token | `application.yaml` 的 `ai.scenes.<scene>.*`，或管理端场景绑定（优先） |
| 加一个工具 | 写 `@Tool` 方法 → 类上 `@Component` → 把工具名加进 `ai.tools.allow-list` |
| 加一个场景 | `SceneCode` 加枚举 → yaml 加 `ai.scenes.<key>` → 提示词文件 → 三处数量断言 |
| 换模型供应商 | `ChatModelFactory` 加 `buildXxx`，其余不动 |
| 改上下文装配策略 | `ChatHistoryAssembler.assemble`（预算与裁剪只在这里） |
| 改记忆抽取时机 | `ChatCompletionServiceImpl` 的 done 分支（`looksExplicit`）/ `ChatSessionServiceImpl.openNew` |
| 加审核类别 | `ModerationCategories` + 提示词类别清单（两处必须同步） |
| 查「为什么这轮花了这么多 token」 | `chat_message.prompt_tokens` + `chat_context` 分片 + `chat_tool_call` 记账 |
