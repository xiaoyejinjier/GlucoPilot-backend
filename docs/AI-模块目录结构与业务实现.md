# AI 模块：目录结构评审与业务实现梳理

> 范围：`glucopilot-business/src/main/java/com/threadfit/glucopilot/ai`（128 个 java 文件，13 400 余行；12 个 controller、9 个 mapper）
> 版本：2026-11-03，Flyway v51
> 本文分两部分：第一部分判「类放得对不对、还能不能更细」，第二部分按业务链路讲「谁依赖谁、关键方法做什么」。
>
> **执行状态（2026-11-03）**：M1–M7 已全部落地（提交 `3423969` → `7d99bf7` → `90aa508` → `7009244` → `9702792` → `a5a433c`），
> `ai/core` 与 `ai/guard` 已消失，66 个包全部有 `package-info`，全量单测 866 绿、打包启动正常。
> 仅 M8（拆分 `ChatCompletionServiceImpl`）按计划保留未做。

---

## 第一部分 · 目录结构评审

### 0. 结论先说

**总体是合规的**：分层清楚（接入 → 业务 → 内核 → 持久化），业务域与后台管理域都是 controller / dto / entity / mapper / service / impl 的规整结构，事务边界、日志、异常都符合公司 Java 手册的分层约定。

**问题不在「乱」，在四处「包名与内容名不副实」**：

| # | 现象 | 具体证据 | 影响 |
|---|---|---|---|
| 1 | `config/` 里混了值对象 | `AiModelCapability` 被 7 个包使用（core、model/dto、model/mapper、model/runtime、model/service/impl、scene/service/impl），它是个**领域值对象**，却住在配置包 | 想复用「能力」这个概念的人，得先知道它藏在 config 里 |
| 2 | `support/` 是杂货铺 | 4 个类里 3 个**只被 `business/chat/service/impl` 使用**：`EnglishPreambleGuard`、`OwnedMediaResolver`、`SqlDuplicateKeys`；只有 `ChatRoundGuard` 是 chat + memory 共用 | 跨域工具包名不实，删改 chat 时不敢动 support |
| 3 | Advisor 散在三处、命名还不一致 | `guard/MedicalGuardAdvisor`（包名没有 advisor）、`prompt/advisor/DynamicPromptAdvisor`、`memory/advisor/{ChatMemoryReadAdvisor, ChatContextReader, ChatMemoryAdvisorParams}` | 加一个 advisor 要猜「该放哪个包」；Spring AI 自己把所有 advisor 放同一个包 |
| 4 | `core/` 名不副实 | 3 个类三种角色：`SceneCode`（枚举契约）、`SceneModelResolver`（379 行路由）、`AiChatClientRegistry`（client 取用） | 「core」是最容易变成第二个杂货铺的名字 |

另有 4 处**可以细分**（不是错，但平铺已经到极限）：

| # | 目录 | 现状 | 细分成 |
|---|---|---|---|
| 5 | `tool/` | 10 个文件平铺：5 个工具实现 + 5 个执行支撑（`AiToolRegistry`、`TracingToolCallback`、`AiToolContext`、`ToolCallTraceRecorder`、`ToolResultDigester`） | `builtin/` + `runtime/` + `trace/` |
| 6 | `memory/assembly/` | 装配器 `ChatHistoryAssembler` + `TokenEstimator`（chat 也在用）+ `ChatContextShard`（chat 的 entity/service 与 memory 的 advisor 都在用） | 装配器留下，另两个归位 |
| 7 | `business/speech/` | 4 个文件平铺，是唯一没有子包的域（chat/meal/ocr 都有 controller / service / dto） | 与其它域同构 |
| 8 | `core/`（改组） | 见 #4 | 拆成 `client/`、`routing/`，`SceneCode` 并入 `scene/` |

**参考**（`spring-projects/spring-ai` 的 `spring-ai-client-chat`）：`ai/chat/client` 按**关注点**分包——`client`（ChatClient/Builder/Request/Response）、`client/advisor`（**所有** advisor 平铺，含 ToolCallAdvisor、StructuredOutputValidationAdvisor、MessageChatMemoryAdvisor…）、`client/advisor/api`（SPI）、`client/advisor/observation`（观测）；**每个包都有 `package-info.java`**。它的启示只有两条：①同一角色的类放同一个包（advisor 不做二次分类）；②包要有 `package-info` 说明职责。

### 0.1 逐包判定

| 目录 | 判定 | 依据 |
|---|---|---|
| `business/chat`（22） | 合规 | controller / dto / entity / mapper / service / impl 齐备，无跨域类 |
| `business/meal`（7） | 合规 | 有 `package-info`，职责单一（分析 + 食品库） |
| `business/ocr`（8） | 合规 | 有 `package-info`，异步任务与配置齐备 |
| `business/speech`（5） | **可细分** | 文件平铺（Dtos / Properties / Controller / Service），与其它域不同构 |
| `config`（9） | **错位** | 装配 5 个 + `@ConfigurationProperties` 2 个 + 值对象 `AiModelCapability`、`AiChannel` |
| `core`（3） | **错位** | 三类三种角色（枚举契约 / 路由 / client 取用），包名无信息量 |
| `guard`（1） | **错位** | 只有一个 advisor，却与 `prompt/advisor`、`memory/advisor` 三分天下 |
| `memory`（21） | 基本合规 | advisor / assembly / repository / config / CRUD 分离清楚；`assembly` 里混了 `TokenEstimator`、`ChatContextShard` |
| `model`（20） | 合规 | runtime 与 CRUD 已分离；`AiChannelProperties`、`AiModelCapability` 不在本包（见 config） |
| `prompt`（9） | 合规 | advisor 移走后只剩「模板加载 + 后台管理」 |
| `scene`（8） | 合规 | 后台配置 + 绑定；`SceneCode` 反而在 core（见上） |
| `support`（4） | **错位** | 3 个类只被 chat 使用，不是「共用」 |
| `tool`（10） | **可细分** | 工具实现与执行支撑混放、平铺 |
| `vision`（1） | 合规 | 单一入口，跨三个视觉场景复用 |

### 1. 我用的三条判据

1. **一包一角色**：`config` 只放装配与属性绑定，`entity/mapper/dto` 只放持久化与传输结构，值对象跟着它的领域走。
2. **唯一使用方就地下沉**：只被一个域使用的辅助类不算「共用工具」，放到那个域里；`support` 只接受 ≥2 个域在用的类。
3. **包名即职责**：包名要能一眼说清「放什么」，`core`/`common`/`util` 这类名字要么写清判据，要么拆掉。

### 2. 目标目录树（推荐）

```
ai/
├── config/                          @Configuration：Bean 装配（5 个）
│   └── properties/AIProperties      @ConfigurationProperties 根
├── channel/                         AiChannel + AiChannelProperties（协议/连接/凭据）
├── client/AiChatClientRegistry      「拿 client / 模型」的唯一入口
├── advisor/                         全部 ChatClient Advisor
│   ├── DynamicPromptAdvisor         CHAT 场景提示词注入
│   ├── MedicalGuardAdvisor          医疗硬拦截
│   ├── ChatMemoryReadAdvisor        历史/摘要/工具摘要/长期记忆装配
│   └── ChatMemoryAdvisorParams      参数名常量
├── routing/SceneModelResolver       场景 → 模型 + 参数（唯一决策点）
├── scene/                           SceneCode + 后台场景配置与绑定
├── model/
│   ├── capability/AiModelCapability 能力集合值对象（text/vision/tool/stream）
│   ├── runtime/                     AiModelSpec、AiModelCipher、ChatModelFactory、DynamicChatModel
│   ├── controller/ dto/ entity/ mapper/ service/ service/impl/
├── prompt/                          提示词模板加载 + 后台管理（advisor 已移走）
├── memory/
│   ├── assembly/ChatHistoryAssembler + ChatContextShard
│   ├── reader/ChatContextReader     （从 advisor 移出：它是读取器，不是 advisor）
│   ├── repository/ config/ controller/ dto/ entity/ mapper/ service/ service/impl/
├── tool/
│   ├── builtin/                     5 个工具类
│   ├── runtime/                     AiToolRegistry、TracingToolCallback、AiToolContext
│   └── trace/                       ToolCallTraceRecorder、ToolResultDigester
├── support/                         TokenEstimator、ChatRoundGuard（跨域，只有这两个）
├── vision/VisionAnalyzer
└── business/
    ├── chat/{controller,dto,entity,mapper,service,impl,support}
    ├── meal/{controller,dto,service,impl}
    ├── ocr/{controller,dto,entity,mapper,service,impl,config}
    ├── speech/{controller,dto,config,service}
    ├── moderation/{dto,support,service,service/impl}
    └── translation/{service,service/impl}
```

`business/` 下前四个是「带 HTTP 与表的业务域」，后两个是「收束进来的 AI 能力」：它们没有 controller、不碰业务表，只接收 `ModerationRequest` / `(kind, source)` 并返回判定与译文。这个区分是刻意的——能力包可以被任何业务模块调用，业务包不能反向进 `ai`（`AiModuleBoundaryTest` 守）。

两个能力包的内部划分和其它业务域同一套规则，只是没有 controller/entity/mapper：

| 子包 | 放什么 | moderation | translation |
|---|---|---|---|
| `dto/` | 跨越服务接口的数据结构 | `ModerationRequest`、`ModerationResult`、`ModerationVerdict` | —（入参就是 `(kind, Map)`） |
| `support/` | 无状态辅助件（解析、词表、校验） | `ContentModerationParser`、`ModerationCategories` | —（校验是实现的私有方法） |
| `service/` | 接口 | `ContentModerationService` | `EnglishTranslator` |
| `service/impl/` | 实现 | `ModelContentModerationService` | `ModelEnglishTranslator` |
| 包根 | `package-info.java` | 能力边界说明 | 能力边界说明 |

顶层包从 14 个（business、config、core、guard、memory、model、prompt、scene、support、tool、vision + business 下 4 个）变成 13 个，但**每个包的判据都能一句话说清**，且 `core`/`guard` 这两个「说不清」的名字消失。

### 3. 迁移清单（按性价比排序）

| 步骤 | 动作 | 状态 | 提交 |
|---|---|---|---|
| M1 | `support/` 拆解：3 个只被 chat 用的 → `business/chat/support/`；`ChatRoundGuard`、`TokenEstimator` 留 `support/`；`ChatContextShard` → `business/chat/dto/` | ✅ 已完成 | `3423969` |
| M2 | `AiModelCapability` → `model/capability/`；`AiChannel`＋`AiChannelProperties` → `channel/`；`AIProperties` → `config/properties/` | ✅ 已完成 | `7d99bf7` |
| M3 | Advisor 归并到 `ai/advisor/`；`ChatContextReader` → `memory/reader/` | ✅ 已完成 | `90aa508` |
| M4 | `tool/` 分 `builtin`/`runtime`/`trace` | ✅ 已完成 | `7009244` |
| M5 | `business/speech/` 补 `controller`/`dto`/`config`/`service` 子包 | ✅ 已完成 | `7009244` |
| M6 | 拆 `core/`：`AiChatClientRegistry` → `client/`；`SceneModelResolver` → `routing/`；`SceneCode` → `scene/` | ✅ 已完成 | `9702792` |
| M7 | 每个包补 `package-info.java`（66 个包全覆盖） | ✅ 已完成 | `a5a433c` |
| M8 | （可选）`ChatCompletionServiceImpl` 约 660 行 → 抽出 `ChatStreamEvents`（SSE 事件拼装）与 `ChatTurnPersister`（落库 + 分片记录） | ⏸ 未做（按计划保留） | — |

**为什么不做**：不引入 `domain/application/infrastructure` 这类四层 DDD 目录——本项目是 MyBatis 直连 + 单一 Spring 应用，强行分层会让 118 个类里 60 个「无处安放」；Spring AI 与 Anthropic 的经验都是「按关注点分包、别按抽象层次分包」。

**必须注意的两个硬约束**：

1. **MyBatis 映射扫描**：`glucopilot-adapter/.../PersistenceConfiguration.java:37-41` 用 `markerInterface = BaseMapper.class` 扫描，所以 mapper 接口必须**继承 `BaseMapper`**，且包路径必须落在它扫描的根下。移动 mapper 时若离开了扫描根，启动会报「required a bean of type ...Mapper that could not be found」（B 批的 `AiUsageMapper` 就踩过）。
2. **`@MapperScan` 显式列表**：`GlucoPilotBackendApplication` 里为 `upstream-standalone` profile 单独列了 `com.threadfit.glucopilot.ai.business.chat.mapper`，移动 chat mapper 时这条要同步。

---

## 第二部分 · 业务实现梳理

### 4. 分层与依赖方向

```
L0 接入层      ChatController / MealAnalysisController / OcrTaskController / SpeechTranscriptionController
               Admin*Controller（模型、场景、提示词、用量、诊断、上下文审计）
L1 业务层      business.chat / meal / ocr / speech 的 ServiceImpl；community 模块的 AI 侧调用
L2 运行时内核  AiChatClientRegistry → ChatClient（text / summary / vision）
               SceneModelResolver（场景→模型+参数） · AiPromptTemplateService（提示词）
               ChatCompletionServiceImpl / ChatSummaryServiceImpl / UserChatMemoryServiceImpl
               advisor 链 · 工具链 · VisionAnalyzer
L3 配置与持久化 AiRuntimeConfigService（模型快照） · AiSceneBindingService（场景绑定）
               mapper / entity / repository
```

依赖**只向下**，唯一例外是 `memory`、`tool` 依赖 `business.chat` 的 `ChatMessageMapper` / `ChatSessionService` / `ChatToolCall`——记忆与工具调用记录本质是「对话的表」，这个方向是有意接受的。

---

### 5. 业务一：对话（CHAT / CHAT_IMAGE）

**类与依赖**

```
ChatController ──┬─→ ChatSessionServiceImpl ──→ ChatSessionMapper / ChatMessageMapper
                 ├─→ ChatMessageServiceImpl ──→ ChatMessageMapper / OwnedMediaResolver / ChatSessionService
                 └─→ ChatCompletionServiceImpl ─┬─→ AiChatClientRegistry → textChatClient
                                                ├─→ ChatMessageMapper / ChatSessionService / ChatMessageService
                                                ├─→ VisionAnalyzer（图片轮阶段 1）
                                                ├─→ ChatRoundGuard（单飞）/ TokenEstimator（预算）
                                                ├─→ ChatSummaryService / UserChatMemoryService（异步后处理）
                                                └─→ ChatContextRecorder（分片落库）
```

**关键方法**

| 方法 | 位置 | 做什么 |
|---|---|---|
| `getOrCreate(userId, sessionType)` | `ChatSessionServiceImpl:85` | 取当前活跃会话；`ChatController.current`（`:48`）在 `forceNew` 时改调 `openNew` |
| `openNew()` | `:113` | 结束旧会话 → `extractMemoryQuietly()` 旁路抽取记忆 → 插入新会话（`session_no` 递增） |
| `requireActive(userId, sessionId)` | `:361` | 已结束/归档的会话不允许再写；抛 `BizException` |
| `list` / `delete` / `setPinned` | `:205/:294/:314` | 会话生命周期；`list` 会懒归档空闲会话（`ai.chat.archive-idle-days`） |
| `appendUserMessage` | `ChatMessageServiceImpl:71` | 校验 payload → 校验媒体归属 → `insertWithSeqNo`（唯一键冲突重试）→ 触达会话时间戳 → 默认标题改名 |
| `complete(userId, sessionId, request)` | `ChatCompletionServiceImpl:179` | 六步：requireActive → 定位目标消息（可回复历史消息）→ 幂等回放（`selectAssistantReply`）→ 单飞锁 → 开流 → `doFinally` 兜底释放 |
| `startStream` | `:225` | 组装 prompt（advisor 参数 + toolContext）→ 订阅 `chatResponse()` → 聚合 delta → done 阶段落库、标定估算、异步摘要/记忆抽取 |
| `persist` | `:424` | 写助手消息（`model_code`、tokens、`finish_reason`、`system_prompt_ver`），先置 `state.persisted=true` 再写库 |
| `mapFinishReason` | `:636` | `stop→NORMAL`、`length→TRUNCATED`、`content_filter→BLOCKED`、超时/异常→ERROR |
| `imageTurn` / `stageTwoText` | `:588/:616` | 图片轮两阶段：先 `VisionAnalyzer.describe` 出 130 字描述，再把描述与用户问题合成阶段 2 文本 |

**一次流式补全的关键顺序**（顺序错了会重复计费或重复落库）：

```
requireActive → 幂等检查 → tryAcquireToken(roundKey) → [视觉阶段1] → prompt(...).stream()
  → doOnNext(aggregate) → mapNotNull(deltaEvent)  ──SSE──▶ 客户端
  → done: persist → calibrateEstimate → 摘要异步 → 记忆抽取异步 → release(roundKey, token) → doneEvent
```

### 6. 业务二：饮食分析（MEAL_ANALYSIS）

**依赖**：`MealAnalysisController → MealAnalysisServiceImpl`，字段 6 个：`MediaFileService`、`VisionAnalyzer`、`MealFoodCatalogService`、`SceneModelResolver`、`JsonUtils`、`IdempotencyStore`。

**`analyze(userId, request)` 九步**：节点校验（早餐/中餐/晚餐）→ 媒体归属 → 幂等键（`meal:analysis:<mediaId>:<foodHash>`）→ **模型取自 `sceneModelResolver.modelCode(MEAL_ANALYSIS)`**（回显与溯源必须与真实调用同源）→ `vision.analyze(...)` 拿 JSON → 解析（去 ```json 围栏、`schemaVersion==1`、foods ≤12）→ 逐条经 `MealFoodCatalogService` 归一化并回填营养（缺重量/碳水/营养时追加 warnings）→ 空结果 400 → 汇总碳水/热量并 `idempotency.complete`。

### 7. 业务三：血糖 OCR（OCR_GLUCOSE）

**依赖**：`OcrTaskController → OcrTaskServiceImpl`（`OcrTaskMapper`、`MediaFileService`、`JsonUtils`、`IdempotencyStore`、`@Qualifier("ocrTaskExecutor") Executor`）。

- `create`：业务类型只支持 GLUCOSE → 媒体归属 → 幂等 → 插 `PENDING` 任务 → **异步** `recognize`。
- `recognize`（私有）：`RUNNING` → `VisionAnalyzer.analyze(media, OCR_GLUCOSE, 指令)` → 截取 `{...}` → `normalize`（schema 1 + 业务类型）→ `SUCCEEDED(json)`；失败 `FAILED(安全化错误)`。
- `purgeAccountData`：账号注销时**物理删除**（健康数据不做逻辑删除）。

### 8. 业务四：语音转写（ASR）

**不是 ChatClient 场景**：`SpeechTranscriptionService` 用裸 `RestClient` 打 dashscope，字段含 `Set<Long> inFlight`（同用户单飞）、`Semaphore(8)`（全局并发）。`transcribe` = 校验音频 → 单飞 + 容量闸 → HTTP 调用 → finally 释放。密钥优先 `ai.speech.api-key`，为空才回落 `ai.vision.api-key`（借视觉凭据，是历史写法）。

### 9. 记忆与摘要（MEMORY / SUMMARY）

**摘要**：`ChatCompletionServiceImpl` done 阶段调 `chatSummaryService.maybeSummarizeAsync(userId, sessionId)`；`ChatSummaryServiceImpl:runSummarize` 读会话已有摘要 → 取候选消息（保留最近 keep 条不参与）→ 用 **utilityChatClient**（无工具、无 advisor）生成 → 截断到 `summary-max-chars` → `updateSummary(sessionId, text, uptoId)`（`upto` 指针防重复摘要）。

**长期记忆**：`extractAsync` 单飞（`chat-memory-extract:<sessionId>`）→ 读最近 N 条消息 → 拼「已知记忆 + 对话原文」→ 生成 → **C 批起可走结构化输出**（`call().entity(MemoryPayload.class)` + `StructuredOutputValidationAdvisor`）→ 候选过四道闸（键白名单/置信度/危险词/长度）→ `dedupeByKey` → `upsert`。注入侧由 `ChatMemoryReadAdvisor` 取 `forInjection(userId)` 拼进系统消息。

### 10. 提示词（模板加载 + 注入）

- 加载：`AiPromptTemplateServiceImpl` 启动时先加载 classpath `prompts/<key>/system.md` 作为**兜底**（并试渲染校验，写坏则启动失败），再从 `ai_prompt_template` 覆盖；`resolve(scene)` 的优先级是 **DB → classpath → 抛异常**；60s TTL + 后台保存时 `refreshNow()`。
- 注入：**CHAT 走 advisor**（`DynamicPromptAdvisor` 每请求替换 SystemMessage，因此后台改提示词不用重启）；**其余场景走请求级 `.system(...)`**（SUMMARY/MEMORY/视觉/审核）。
- 约束：提示词正文**不能出现成对花括号**（ST 模板语法），所以审核场景用「逐行字段清单」而不是 JSON 示例。

### 11. 场景与模型配置（路由 / 诊断 / 用量）

| 类 | 职责 | 关键方法 |
|---|---|---|
| `AiRuntimeConfigServiceImpl` | 模型清单快照（DB 优先，yaml 兜底） | `snapshot()`（60s TTL）、`pickDefault(candidates, fallback, pool)`、`toSpec(row)` |
| `AiSceneBindingServiceImpl` | 场景绑定快照 | `binding(scene)`（永不 null，未配置返回 `SceneBinding.INHERIT`） |
| `SceneModelResolver` | 场景 → 模型 + 参数（唯一决策点） | `modelCode(scene)`、`judgeBinding(scene)`、`optionsFor(scene)`、`quietOptions(...)`（摘要/记忆关思维链） |
| `AiChatClientRegistry` | client / model 取用 | `textClient()`、`utilityClient()`、`utilityAvailable()`、`visionClient(scene)` |
| `AiRuntimeStatusServiceImpl` | 「为什么是这个模型」 | `status()`：逐场景给 `source=binding/default/fallback` + `issues` |
| `AdminAiUsageController` / `AiUsageServiceImpl` | 用量（天 × 模型） | `report(start, end)`，只统计 `chat_message` 已落库 token |

**路由链**：场景绑定（DB，视觉还要求能力含 vision）→ 用途默认（DB：`is_default` → 唯一启用 → 最近修改）→ yaml 通道兜底。

### 12. 内容审核（AI 侧在 `ai/business/moderation`，表在 community）

- `CommunityModerationWorker`（`@Scheduled`，5s 轮询）：`queue.claim()` → `moderation.judge(ModerationRequest)` → `queue.finish(...)`；**只做节拍，不碰提示词/模型/解析**，任何异常都落 ERROR，不放行。
- `ContentModerationService`（接口）/ `ModelContentModerationService`（实现）：`clients.utilityClient()` + `prompts.systemPrompt(MODERATION)` + 45s 超时 + `ContentModerationParser.parse`；**接口承诺不抛异常**，失败返回 `ERROR`。
- `CommunityModerationQueue`：`claimArticle`/`claimComment`（`FOR UPDATE SKIP LOCKED`，3 分钟僵尸回收）、`finishArticle`/`finishComment`（状态 + 版本条件更新）、`insertHits`、`setCommentHidden`（人工隐藏/恢复，「只升不降」：机器能加严、不能替人放宽）。
- 中英翻译同理：`LocalizationService` 管缓存/租约/权限闸，`ai/business/translation/ModelEnglishTranslator` 负责「文本进英文出」，提示词在 `ai_prompt_template` 的 `TRANSLATION` 场景（V54 播种）。

### 13. 横切：工具链 / 护栏 / 观测

- **工具链**：`AiToolRegistry.callbacks()` 按 `ai.tools.allow-list` 过滤（白名单为空则一个不注册），每个工具套 `TracingToolCallback` 记录耗时与结果摘要（`ToolResultDigester` 只存摘要，不存原文）；`AiToolContext` 用 `toolContext` 传递 `userId/sessionId/userMsgId/计数器`，`requireUserId` 缺失直接抛（防模型诱导越权）。
- **护栏**：`MedicalGuardAdvisor`（`HIGHEST_PRECEDENCE+100`）只扫**最后一条用户消息**，命中敏感词直接返回固定话术，不调模型。
- **观测**：`ChatModelFactory` 挂 `ObservationRegistry`/`MeterRegistry`；`chat_message` 落 `prompt_tokens/completion_tokens/latency_ms`，B 批起有 `GET /admin/v1/ai/usage` 消费。

### 14. 场景 ↔ client 对照（改代码前先看这张表）

| 场景 | 入口 | 用哪个 client / 模型 |
|---|---|---|
| CHAT | `ChatCompletionServiceImpl:253` | `registry.textClient()`（带工具 + 记忆 + advisor） |
| CHAT_IMAGE | `VisionAnalyzer:73`（由 `ChatCompletionServiceImpl:598` 调起）→ 阶段 2 回 `ChatCompletionServiceImpl:253` | `visionChatClient` → `textChatClient` |
| MEAL_ANALYSIS | `MealAnalysisServiceImpl:67` | `visionChatClient` |
| OCR_GLUCOSE | `OcrTaskServiceImpl:134` | `visionChatClient` |
| SUMMARY | `ChatSummaryServiceImpl:263`（`maybeSummarizeAsync` 在 `:100`） | `utilityChatClient`（与 MEMORY 共用） |
| MEMORY | `UserChatMemoryServiceImpl:396`（`utilityAvailable()` 在 `:279/:308`） | `utilityChatClient` |
| MODERATION | `CommunityModerationWorker` → `ModelContentModerationService` | `utilityChatClient`（提示词与参数取自 `ai.scenes.moderation`） |
| TRANSLATION | `LocalizationService:302` → `ModelEnglishTranslator` | `utilityChatClient`（`ai.scenes.translation`，`json: true`） |
| 语音转写 | `SpeechTranscriptionService:52` | 裸 `RestClient` → dashscope ASR |

---

## 附：如果要动，建议的顺序

1. **M1/M2/M6/M7**（半天，纯移动 + `package-info`）：风险最低，先把「名不副实」和「平铺」解决。
2. **M3/M5**（半天）：advisor 归并、tool 分层；改完跑一次全量单测即可。
3. **M4**（半天）：拆 `core`，`SceneCode` 的 import 波及面最大，单独一个提交便于回滚。
4. **M8**（1 天，可选）：`ChatCompletionServiceImpl` 拆事件拼装与落库；必须跑 `session-e2e`（36 条）与 `moderation-e2e`（56 条）。
