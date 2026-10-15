# AI 对话「简单接入」方案

> 目标：用最小改动让小程序能和大模型真正对话（流式），消息可落库、可多轮。
> 定位：本方案是《AI 模块开发方案》的**落地第一步**，只做「简单对话」，不碰 OCR / 预测 / 工具调用 / RAG。
> 基线：`backend/pom.xml`（Boot 4.1.1 + Spring AI 2.0.1）、`backend/sql` + `db/migration` V1（38 表）、`接口设计文档.docx`（2.23 / 2.24 / 2.25）、`CURSOR.md`（硬契约）。

---

# 1. 现状核对（开工前先看清家底）

## 1.1 已经做好的部分

| 项 | 状态 | 位置 |
|---|---|---|
| Spring AI 依赖 | ✅ 已加（BOM 2.0.1 + `spring-ai-starter-model-deepseek`） | `pom.xml`（未提交） |
| 编译 | ✅ **实测 BUILD SUCCESS**：89 个源文件，Boot 4.1.1 + Spring AI 2.0.1，耗时 4 分 13 秒 | — |
| 配置类 | ✅ `AIProperties` / `ChatClientConfig` / `PromotesLoader` | `ai/config`、`ai/utils` |
| 系统提示词 | ✅ `promotes/agent/health_agent_system.md`（含角色、能力边界、禁止行为、免责声明） | `resources/promotes/agent` |
| 会话/消息等表 | ✅ 四表（`chat_session`/`chat_message`/`chat_context`/`chat_user_memory`）已在 Flyway V1 建好，**本次无需新增迁移** | `db/migration/V1__init_schema.sql` |
| 前端对话页 | 🟡 `pages/voice` 已成形，但回复是 `setTimeout` + 硬编码数组（mock） | `frontend/pages/voice/voice.js` |

## 1.2 还是空的、本次要填的

`ai/business/chat/{session,message,context,speech}` 下只有 `.gitkeep`，无任何实体、Mapper、Service、Controller。

## 1.3 依赖自带能力（省得自己写）

扫描 Spring AI 2.0.1 的 jar 确认：

- `spring-ai-starter-model-deepseek` 传递引入了 `spring-ai-autoconfigure-model-chat-client` 与 `spring-ai-autoconfigure-model-chat-memory` → **`ChatClient.Builder` 与 `ChatMemory` 都有自动装配**，不需要自己 new。
- 它也带来 `spring-boot-starter-webclient`（Reactor 在 classpath 上），`ChatClient.stream()` 的 `Flux` 可以直接用；服务仍跑 Spring MVC（`spring-boot-starter-web`），不必改成 WebFlux。
- DeepSeek 默认 `base-url = https://api.deepseek.com`（官方端点）。

---

# 2. 目标与非目标

## 2.1 本次目标（Definition of Done）

用户在小程序对话页发一条文字 → 服务端带系统提示词调 DeepSeek → **流式逐字返回** → 消息落到 `chat_session`/`chat_message` → 同一会话内可多轮追问。

## 2.2 本次明确不做

语音 ASR/TTS、图片消息、工具调用、RAG / 个人知识库、上下文压缩摘要、跨会话长期记忆、限流与配额、后台 `ai_model_config` / `ai_prompt_template` 表驱动（本次仍用 yaml + classpath md）、token 成本看板、消息列表分页与删除会话。

---

# 3. 必须先修的阻塞问题（已逐条验证）

## 3.1 【P0】配置绑定不匹配 → 应用启动直接 NPE

| 项 | 内容 |
|---|---|
| yaml 写的 | `ai.prompts.agent-system-file` |
| 代码要的 | `AIProperties` → `prompts` → `agentPrompts` → `agentSystemFile`，即 **`ai.prompts.agent-prompts.agent-system-file`** |
| 后果 | `agentSystemFile` 绑定不上 → 值为 `null` → `ChatClientConfig` 调 `PromotesLoader.loadPromptWithFallback(null, path)` → `Path.of(null)` 抛 `NullPointerException` → `chatClient` Bean 创建失败 → **应用起不来** |
| 验证 | 本机 JDK 21 实测：`Path.of((String) null)` 抛 `java.lang.NullPointerException` |

**修法（推荐 B，扁平化）**

- 方案 A：yaml 改成 `ai.prompts.agent-prompts.agent-system-file`（能跑，但多一层没意义的嵌套）。
- **方案 B（推荐）**：删掉 `agentPrompts` 中间层，让字段与 yaml 一一对应：

```java
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AIProperties {
    private Prompts prompts = new Prompts();

    @Data
    public static class Prompts {
        private String agentSystemFile;      // ai.prompts.agent-system-file
        private String memoryChatFile;       // ai.prompts.memory-chat-file
        private String toolCallSystemFile;   // ai.prompts.tool-call-system-file
    }
}
```

**同时加固 `PromotesLoader`**（不改的话以后任何一处传 null 都会炸）：

```java
public static String loadPromptWithFallback(String externalPath, String classpathPath) {
    if (externalPath != null && !externalPath.isBlank()) {
        Path path = Path.of(externalPath);
        if (Files.isReadable(path)) {
            try { return Files.readString(path, StandardCharsets.UTF_8); }
            catch (IOException ignored) { /* 落回 classpath */ }
        }
    }
    if (classpathPath == null || classpathPath.isBlank()) {
        throw new IllegalStateException("提示词路径未配置：classpathPath 为空");
    }
    return loadPrompt(classpathPath);   // 读不到要给带路径的可读异常，别吞
}
```

## 3.2 【P1】模型配置路径写错 → 配的模型不生效（静默）

> **⚠️ 本节结论已于 2026-09-23 修正：原文判断有误。** 保留标题以维持章节编号稳定。
>
> 原文称「只有 `chat.options.model` 生效，写 `chat.model` 会静默回落默认模型」——**这是错的**。
> 用 `javap` 核实 `spring-ai-autoconfigure-model-deepseek-2.0.1.jar` 的
> `META-INF/spring-configuration-metadata.json`：`DeepSeekChatProperties` 的
> `CONFIG_PREFIX = "spring.ai.deepseek.chat"`，`model` / `temperature` / `max-tokens` /
> `thinking` / `reasoning-effort` / `response-format` 都是**该类自身的字段**，
> 同时它还内嵌 `Options` 类（前缀 `...chat.options`）。两套路径**都能绑定**：

| 路径 | 是否生效 | 用途 |
| --- | --- | --- |
| `spring.ai.deepseek.chat.model` | ✅ 生效 | 主路径（当前项目用的就是这条） |
| `spring.ai.deepseek.chat.options.model` | ✅ 也生效 | 供 `ChatOptions` 默认值使用 |

> 可绑定的属性共 25 个（两套各 12 个，另加 `chat.enabled`）。
> **请勿再按原文把 `chat.model` 改成 `chat.options.model`** —— 现在的写法本来就是对的。

## 3.3 【P1】模型名 `deepseek-v4-flash` 需要确认

> **⚠️ 本节结论已于 2026-09-23 修正：原文判断有误。**
>
> 原文称 `DeepSeekApi$ChatModel` 只枚举了 `deepseek-chat` 与 `deepseek-reasoner`。
> 实测 2.0.1 的枚举有**四个**值，`value` 如下：
>
> | 枚举 | value |
> | --- | --- |
> | `DEEPSEEK_V4_FLASH` | `deepseek-v4-flash` |
> | `DEEPSEEK_V4_PRO` | `deepseek-v4-pro` |
> | `DEEPSEEK_CHAT` | `deepseek-chat` |
> | `DEEPSEEK_REASONER` | `deepseek-reasoner` |
>
> 当前 yaml 原用 `deepseek-flash`，**实测可通**（L1 记录里响应回显 `model=deepseek-flash`），
> 但它不在枚举内，属于「`model` 是字符串、可填任意值」的用法。
>
> ✅ **已于 2026-09-23 决策并落地**：统一改用官方枚举名 `deepseek-v4-flash`
> （`spring.ai.deepseek.chat.model`）。该改动**尚未经真实调用验证**，
> 第一次联调时须确认模型可用、并对比时延与 token 与旧值无异常差异。

## 3.4 【P0-安全】API Key 明文写在 `application.yaml`

当前 yaml 里是明文的 `sk-7bde…`（未提交，但随时可能被 `git add .` 带进去）。

- 改成 `${DEEPSEEK_API_KEY:}`，本地值放 `application-local.yaml` 并加入 `.gitignore`；或直接走系统环境变量。
- **如果这个 Key 曾经提交过 git，请去 DeepSeek 控制台轮换。**（顺带确认额度/余额）
  （2026-09-23 复查：`git log --all -S 'sk-'` 与全历史 `git grep` 均未命中 `sk-` 明文，
  可以暂不轮换；但生产仍缺 Key 的注入方式，见 §16。）
- 日志里要过滤 `Authorization` / `api-key`，避免请求日志把 Key 打出来。

## 3.5 【P2】`builder.defaultAdvisors()` 是空调用

`defaultAdvisors(Advisor... advisors)` 是可变参数，不传参数等于**什么都没做**，留着会误导后来人以为已经挂了记忆/审计。先删掉，等真要用 `MessageChatMemoryAdvisor` 时再挂。

> ✅ **已解决（2026-09-23）**：现在 `AiChatClientConfig` 挂的是真实 Advisor 链
> （`SafeGuardAdvisor` + `ChatMemoryReadAdvisor`），见 §15。

## 3.6 【P2】两个 0 字节提示词文件

`promotes/agent/memory_chat.system.md`、`tool_call_prompt.system.md` 都是 0 字节。要么填内容，要么删掉，避免以后读到空系统提示词导致"模型像换了个人"。

> ✅ **已解决（2026-09-23）**：两个文件已随 `resources/promotes/` 整体删除；
> 提示词改为按场景组织在 `resources/prompts/{chat,meal-analysis,ocr-glucose}/system.md`，
> 且启动时按声明的变量试渲染一次，空文件/花括号写错都会直接启动失败，见 §15。

---

# 4. 分两级推进

## 4.1 L0 冒烟（0.5 人日）：只证明「能通」

1. 修 §3.1 / §3.2 / §3.3 / §3.4。
2. 加一个**临时**端点（不写进接口文档，L1 完成后删除或 `@Profile("dev")` 保留）：

```
POST /api/v1/chat/smoke
Body: { "message": "你好" }
Resp: { "code":0, "data": { "reply": "…", "model": "…", "latencyMs": 1234 } }
```

非流式，`chatClient.prompt().user(msg).call().content()`，**不落库**。

**验收（三条全过才算通）**

1. 返回一句通顺中文回复；
2. 问「格列本脲一次吃几片」→ 回复**引导就医、不给剂量**（证明系统提示词真的生效了，不是读了个空文件）；
3. 日志里能看到实际使用的 model 名、耗时、token 数。

> 这三点能一次性排掉：Key 无效、模型名错、提示词没加载、超时。

## 4.2 L1 简单对话（2~3 人日）：三接口 + 流式 + 落库

**严格按《接口设计文档》实现，不新造契约**（CURSOR.md 规定接口文档是第 1 优先级权威源）：

| 编号 | 接口 | 作用 | 关键点 |
|---|---|---|---|
| 2.23 | `POST /api/v1/chat-sessions/current` | get-or-create「进行中」会话 | 入参 `sessionType`（默认 3 混合），返回 `{id, sessionStatus, title}` |
| 2.24 | `POST /api/v1/chat-sessions/{sessionId}/messages` | 写用户消息 | `clientMsgId` 必填，幂等；返回 `{id, seqNo}` |
| 2.25 | `POST /api/v1/chat-sessions/{sessionId}/completions` | SSE 流式补全 | `Accept: text/event-stream`；事件 `delta` / `done` / `error`；`done` 必须带完整正文 |

**必须遵守的硬约定（来自 `CURSOR.md`）**

- SSE **不要包 `ApiResponse`**（会把流打断），错误也走 `error` 事件；
- 成功响应 HTTP 200 + `code=0`；失败用真实 HTTP + 五位 code，**禁止 200 包失败**；
- Long 在 JSON 里序列化为**字符串**（`BaseEntity` / `ApiResponse` 已有 `ToStringSerializer`）；
- 未登录走 `RequestContext.requireUserId()` → 401 / `40100`；
- 越权访问他人 sessionId → 404 / `40400`（不是 403，避免泄露存在性）。

---

# 5. 代码落地清单（对齐现有 `ai/business/**` 结构）

| 包 / 类 | 职责 |
|---|---|
| `ai/business/chat/controller/ChatController` | 三个接口，只做参数校验 + DTO 转换 |
| `ai/business/chat/service/ChatSessionService` | get-or-create、`title` 生成、维护 `msg_count`/`last_msg_at` |
| `ai/business/chat/service/ChatMessageService` | 写用户消息（幂等）、`seq_no` 分配、流结束后写助手消息 |
| `ai/business/chat/service/ChatCompletionService` | 装配历史 → 调 `ChatClient` → 推 SSE 事件 → 落库 |
| `ai/business/chat/context/ChatContextAssembler` | L1 简化版：查最近 N 条 → `List<Message>`（见 §6.1） |
| `ai/business/chat/dto/ChatDtos` | 请求/响应 DTO（`@Valid` 校验） |
| `ai/business/chat/entity/ChatSession`、`ChatMessage` | 继承 `BaseEntity`（审计字段 + `@TableLogic`） |
| `ai/business/chat/mapper/ChatSessionMapper`、`ChatMessageMapper` | `extends BaseMapper<T>`，包名必须以 `.mapper` 结尾 |
| `ai/config/ChatClientConfig` | 修好后的 `ChatClient` Bean |
| `ai/utils/PromotesLoader` | 加固后的提示词加载 |

**实体字段映射注意**：`map-underscore-to-camel-case=true` 已能覆盖 `client_msg_id → clientMsgId`、`seq_no → seqNo`、`prompt_tokens → promptTokens`、`reply_to_msg_id → replyToMsgId`。**只有"数字粘连"的列名才需要显式 `@TableField`**（CURSOR.md 记录的坑：`carbsPer100g → carbs_per_100g`）。chat 系列表没有这类列，无需额外注解。

**复用，别重造**：`common/api/ApiResponse`、`common/exception/BizException` + `GlobalExceptionHandler`、`common/context/RequestContext`、`common/util/SqlDuplicateHelper`（1062 判重）、`common/model/BaseEntity`。

---

# 6. 关键实现决策（含取舍理由）

## 6.1 多轮上下文：显式装配，L1 不用 `ChatMemory`

| 方案 | 取舍 |
|---|---|
| `InMemoryChatMemory`（框架默认） | 最快，但**重启即丢**、多实例不一致，且落库时机不可控 |
| 自定义 `ChatMemoryRepository` 落库 | 贴合框架，但仍要在「流结束拿到完整正文 + token 统计」之后再写助手消息，等于自己控时序 |
| **显式装配（L1 采用）** | 查库拿最近 N 条 → 自己拼 `prompt().messages(...)`。时序完全可控、无隐藏状态、行为可预测 |

做法：

```
SELECT ... FROM chat_message
WHERE session_id = ? AND is_deleted = 0
ORDER BY seq_no DESC LIMIT ?     -- ? = chat_session.window_msg_count（默认 20）
```

结果反转成正序 → 映射成 `UserMessage` / `AssistantMessage` → `chatClient.prompt().messages(history).user(current).stream().content()`。

**升级路径**：L2 换成 `MessageChatMemoryAdvisor` + 自定义 `ChatMemoryRepository` 时，Service 对外签名不变。

## 6.2 流式：`SseEmitter` + 虚拟线程（Java 21）

`ChatClient.stream()` 给的是 `Flux<String>`，用 MVC 的 `SseEmitter` 承接，每段增量发一个 `delta` 事件。

**不写就会踩的四个点：**

1. **资源释放**：`SseEmitter` 设 timeout（建议 60s），在 `onTimeout` / `onCompletion` / `onError` 里 `disposable.dispose()` 取消上游订阅。否则用户一返回，上游还在跑，线程与连接泄漏。
2. **别被网关缓冲**：响应头加 `Cache-Control: no-cache`、`X-Accel-Buffering: no`，否则 Nginx 会攒够一整段才吐，看起来"流式失效"。
3. **`done` 必须带完整正文**：前端在网络抖动/断流时能直接用它兜底，不必自己拼 delta。
4. **中断也要落库**：客户端断开后，已生成的内容仍要写进 `chat_message`，`finish_reason` 标 4（错误/中断）。

> ⚠️ **ThreadLocal 陷阱**：`RequestContext` 是 ThreadLocal，而 SSE 的推送回调**不在请求线程上**。必须在进入流式之前把 `userId` / `requestId` 取出来存成局部变量，不要在回调里 `RequestContext.getUserId()`。

## 6.3 幂等与 `seq_no`

- 幂等：`chat_message` 唯一键 `uk_session_client_active (session_id, client_msg_id, is_deleted)`，插入捕获 MySQL 1062 → 查回已有消息直接返回（`SqlDuplicateHelper.isDuplicateKey`）。**好处是重复提交不会重复扣 token。**
- `seq_no`：同事务内 `SELECT COALESCE(MAX(seq_no), 0) + 1`。同一用户同一会话并发极低，够用；L2 要多实例再换号段/Redis。

## 6.4 ⚠️ Redis 已移除，原 AI 方案不能照抄

`CURSOR.md` 明确记录「已移除 Redis（短信冷却用进程内内存）」。所以《AI 模块开发方案》里基于 Redis 的部分在本次要替换：

| 原方案（Redis） | 本次替代 |
|---|---|
| Redis 缓存会话热窗口 | 直接查库（`idx_session_seq` 覆盖，代价可接受） |
| Redis 令牌桶限流 | **本次不做**；要做先用进程内 `Caffeine`/`Semaphore`（单实例有效），多实例再上 Redis |
| Redis 存幂等键 | 本来就用 DB 唯一键，不受影响 |

## 6.5 助手消息落库字段

| 字段 | 取值 |
|---|---|
| `msg_role` | 2（助手） |
| `content_text` | 完整正文（`done` 时写入，不是每个 delta 写一次） |
| `model_code` | 从 `ChatResponse` 元数据取，取不到则写配置值 |
| `prompt_tokens` / `completion_tokens` | 取 usage；上游不返回则按字符估算并在日志留痕 |
| `latency_ms` | 从发起调用到流结束 |
| `finish_reason` | 1 正常 / 2 长度截断 / 3 内容过滤 / 4 错误。**DDL 有 CHECK `IN (1,2,3,4)`，别写别的值** |
| `reply_to_msg_id` | 对应的用户消息 ID |

---

# 7. 前端（微信小程序）改造点

## 7.1 需要一个支持流式的请求方法

`frontend/utils/request.js` 目前只处理 JSON（`success` 里判 `body.code === 0`），**不能用于 SSE**。新增 `requestStream()`：

- `wx.request({ enableChunked: true, ... })` + `res.onChunkReceived`
- **按字节缓冲后再解码**：chunk 边界可能把一行切断，用 `TextDecoder`（或累积 `Uint8Array` 后统一解码），按 `\n\n` 切事件、取 `data:` 后的 payload
- 常见错法：每个 chunk 独立 `String.fromCharCode` / `decodeURIComponent` → 中文变乱码（微信小程序 real device 上必现）

## 7.2 替换 mock

`frontend/pages/voice/voice.js` 删掉 `AI_REPLIES` 常量与 `replyAfterUser()` 的 `setTimeout`，改为：

```
发送 → 本地先插入用户气泡（乐观渲染）
     → POST /chat-sessions/current 拿 sessionId（首次）
     → POST /chat-sessions/{id}/messages 写用户消息（带 clientMsgId）
     → POST /chat-sessions/{id}/completions 开流
     → 每个 delta 更新"最后一条助手气泡"的 content
     → done 后用完整正文落定；error 时给可重试提示
```

`pages/ai` 是结构化页，本次不动。

---

# 8. 验收标准（L1）

| # | 项 | 标准 |
|---|---|---|
| 1 | 流式可见 | `curl -N` 或小程序能看到逐字输出；首字 P95 ≤ 3s |
| 2 | 多轮 | 连续 3 轮追问，模型能引用上一轮内容（如"那我晚饭呢"） |
| 3 | 幂等 | 同 `clientMsgId` 重复提交，`chat_message` 只多 1 条用户消息 |
| 4 | 落库 | 每条助手消息都有 `content_text` + token 数 + `latency_ms` + `finish_reason` |
| 5 | 鉴权 | 无 Token → 401 + `40100`；访问他人 `sessionId` → 404 + `40400` |
| 6 | 安全话术 | 问用药剂量 → 引导就医 + 免责声明；描述急症 → 提示立即就医/拨打急救电话 |
| 7 | 异常 | DeepSeek 400/超时 → `error` 事件 + 前端可重试；后端不吐 500 栈 |
| 8 | 中断 | 用户中途返回 → 上游订阅被取消（日志可见），已生成内容仍落库 |

---

# 9. 待确认（开工前请拍板）

1. **历史回看**：《接口设计文档》2.x 里**没有**「查询消息列表」接口。所以"重新进入对话页能否看到历史"取决于是否新增 `GET /api/v1/chat-sessions/{id}/messages`。
   - 选项 A：L1 不做历史（重进页面只有欢迎语），**契约零变更**；
   - 选项 B：新增该接口，需**先更新《接口设计文档》再实现**（CURSOR.md 要求文档先行）。
   - 建议：A（先跑通对话），把 B 放进 L2。
2. **API Key**：`sk-7bde…` 是否曾经提交进 git？若是，请轮换。
3. **模型名 / 端点**：`deepseek-v4-flash` 是官方 API 的合法 model id，还是第三方中转（需要配 `base-url`）？额度是否够？
4. **会话标题**：`chat_session.title` 谁生成？建议 L1 取首条用户消息前 20 字。
5. **冒烟端点去留**：`POST /chat/smoke` 是 `@Profile("dev")` 长期保留，还是 L1 完成后删除？
6. **本机 Maven 缓存**：`.m2` 里原先最高只到 `spring-boot-starter-parent:4.0.3`，**没有 4.1.1**，离线编译会直接失败。我已联网跑通一次（`BUILD SUCCESS`）把依赖补齐；如果团队其他人本地也报 `Non-resolvable parent POM`，先联网构建一次即可。

---

# 10. 工作量

| 阶段 | 内容 | 人日 |
|---|---|---|
| L0 | 修配置绑定 + NPE 加固 + 模型/Key 修正 + 冒烟端点 + 真实调用验证 | 0.5 |
| L1 后端 | 三接口 + 实体/Mapper/DTO + 流式 + 落库 + 幂等 | 2 |
| L1 前端 | `requestStream()` + voice 页接真流 + 异常与重试 | 1 |
| L1 联调 | 开发者工具 + 真机 chunked 验证（中文乱码、断流、超时） | 0.5 |
| **合计** | | **约 4 人日** |

---

# 11. 后续路线（本次不做，但接口已留好）

`chat_context` 分片审计 → 滚动摘要（`context_summary` / `summary_upto_msg_id`）→ `chat_user_memory` 长期记忆 → 图片消息（`mediaId`）/ 语音消息（`voice_media_id` + ASR）→ 工具调用（查血糖、查营养库；**工具必须白名单**，Spring AI ≥2.0.1 修过一个"提示词注入触发未授权工具调用"的 CVE）→ 后台 `ai_model_config`/`ai_prompt_template` 表驱动替换 yaml → 限流与 token 成本看板 → RAG / 个人知识库。

---

# 12. L0 实施与验证记录（2026-09-17）

## 12.1 已完成

| 项 | 说明 |
|---|---|
| 配置绑定修复 | yaml 已改为 `ai.prompts.agent-prompts.agent-system-file`，与 `AIProperties` 对齐 |
| Key 外置 | `spring.ai.deepseek.api-key: ${deepseek.apiKey}`，值放 `application-local.yaml`；**该文件已加入 `.gitignore`** |
| 提示词空值兜底 | 新增 `SystemPromptProvider`：启动时加载一次并缓存，**路径为空或正文为空直接启动失败**，并打印加载字数；`PromotesLoader` 同步加固（null/blank 短路 + 带路径的可读异常） |
| 空 advisor 调用 | 删除 `ChatClientConfig` 里无参的 `defaultAdvisors()`，改为构造器注入 |
| 冒烟端点 | `POST /api/v1/chat/smoke`（登录态、同步、不落库），新增 `ChatSmokeController` / `ChatSmokeService` / `ChatSmokeDtos` |
| 推理模型调优 | `thinking.type: disabled` + `max-tokens: 2048`（理由见 12.3） |

## 12.2 实测结果（真实调用 DeepSeek）

启动：`java -jar target/glucopilot-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,local --spring.datasource.password=***`
（`dev` 用于激活 `application.yaml`，`local` 用于加载 API Key；见 12.4 遗留问题）

| 验收项 | 结果 |
|---|---|
| 服务启动 | ✅ 15.6s；日志 `系统提示词加载完成：promotes/agent/health_agent_system.md（930 字）` |
| 鉴权 | ✅ 无 Token → HTTP 401 + `40100` |
| 参数校验 | ✅ 空 message → `40000`；超 500 字 → `40000` |
| 正常提问 | ✅ `code=0`，`model=deepseek-flash`，`finishReason=STOP`，`reply` 正文正确，中文字节为合法 UTF-8 |
| 系统提示词生效 | ✅ `systemPromptChars=930`；问"格列本脲一次吃几片"→ 明确**拒答剂量 + 引导就医 + 结尾免责声明** |
| 时延 | ✅ 737ms / 2651ms / 3649ms（关 thinking 后） |

## 12.3 `deepseek-flash` 是推理模型（本次最重要的发现）

> 注（2026-09-23）：该模型名后已统一为官方枚举名 `deepseek-v4-flash`，本节的实测结论
> （关闭思维链快 3.4 倍、`max_tokens` 必须给足以免思维链吃光预算）与模型名无关，仍然成立。

同一问题、同一套 Spring AI 代码对比：

| 配置 | 耗时 | prompt/completion/total | completion 里 reasoning | 正文 |
|---|---|---|---|---|
| 默认（thinking 开启） | 7737 ms | 46 / 380 / 426 | 有 | 正常 |
| `thinking.type=disabled` | 2293 ms | 20 / 31 / 51 | 无 | 正常 |

- **关掉 thinking：快 3.4 倍、token 少 8.4 倍**，正文质量无肉眼差异。故对话场景默认关闭。
- ⚠️ **`max_tokens` 必须给足**：思维链计入 `max_tokens`。实测 `max_tokens=16` 时思维链吃光预算，返回 **`content` 为空字符串 + `finish_reason=length`** —— 线上表现就是"用户看到一个空回复"。故设 `max-tokens: 2048` 兜底。
- 将来饮食分析（2.20）等需要推理的场景，用**每次请求的 `ChatOptions`** 单独开启（`DeepSeekChatOptions.builder().enableThinking()`），而不是改全局配置。

## 12.4 遗留问题（需决策）

`application.yaml` 的 `spring.config.activate.on-profile: dev` 写在**根文档**里，导致**整个文件**只在 `dev` 生效。实测不带 profile 启动直接失败：

```
Failed to configure a DataSource: 'url' attribute is not specified ...
If you have database settings to be loaded from a particular profile you may need to activate it
(no profiles are currently active)
```

影响：IDE 默认运行、`mvnw test`、任何不带 profile 的启动都会挂，报错信息还指向"profile"，排查成本高。建议二选一：

- 方案 A（推荐）：把 `spring.config.activate.on-profile: dev` 从 `application.yaml` 移除，改为新增 `application-dev.yaml` 承载差异项；`application.yaml` 只留通用配置。
- 方案 B：保留现状，但把"必须 `--spring.profiles.active=dev,local`"写进 `CURSOR.md` 的常用命令，避免新人踩坑。

另：`application.yaml` 里 `spring.datasource.password: "123456"` 与本机实际密码不一致，建议把本地密码也放进（已 gitignore 的）`application-local.yaml`，这样 `dev,local` 即可直接启动。

## 12.5 附：JSON 中文与字符集

响应头是 `Content-Type: application/json`（**不带 charset**）。服务端字节是合法 UTF-8（已用原始字节校验），小程序 `wx.request` / axios 均按 UTF-8 解析，无问题；仅注意**别用按 charset 缺省推断 Latin-1 的客户端调试**，否则会看到 `ä½ å¥½` 这类假乱码（本次就误判过一次）。

---

# 13. L1 实施与验证记录（2026-09-17）

## 13.1 配置结构调整（方案 A 已落地）

| 文件 | 变化 |
|---|---|
| `application.yaml` | **移除根文档里的 `spring.config.activate.on-profile: dev`**；保留公共配置与默认可跑通的默认值；顶部加 profile 约定说明 |
| `application-dev.yaml`（新增） | 开发差异项：`baseline-on-migrate`（生产不应开，否则会静默跳过建表与种子）、日志级别 |
| `application-local.yaml` | 补 `spring.datasource.password`；已 gitignore。启动只需 `--spring.profiles.active=dev,local` |

顺带修掉一个日志隐患：`logging.level.com.threadfit.glucopilot.ai: DEBUG` 会把 MyBatis 的 SQL 参数整条打出来，**含用户对话原文与健康数据**（本次在联调日志里肉眼看到了完整回复正文）。已在 `application-dev.yaml` 单独把 `...ai.business.chat.mapper` 压回 INFO，排查 SQL 时临时调 DEBUG。

## 13.2 交付的代码

| 层 | 文件 |
|---|---|
| Controller | `ai/business/chat/controller/ChatController`（2.23 / 2.24 / 2.25） |
| Service | `ChatSessionService`、`ChatMessageService`、`ChatCompletionService`、`ChatSmokeService`(L0) |
| Context | `ai/business/chat/context/ChatContextAssembler` |
| Entity | `ChatSession`、`ChatMessage`（均继承 `BaseEntity`） |
| Mapper | `ChatSessionMapper`、`ChatMessageMapper`（包名以 `.mapper` 结尾，且已在启动类 `@MapperScan` 注册） |
| DTO | `ChatDtos`（会话 / 消息 / delta / done / error） |
| 枚举 | `CodeEnum` 新增 `ChatSessionStatus`、`ChatSessionType`、`ChatMsgRole`、`ChatMsgType`、`ChatFinishReason` |
| 配置 | `AiModelInfo`（当前模型编码单一来源）；`SystemPromptProvider` 增 `fileName()` |

## 13.3 三处与文档示例不同、但更合理的实现决策

1. **助手消息不用 `client_msg_id` 做幂等**。唯一键是 `(session_id, client_msg_id)`，若与用户消息共用同一个 `clientMsgId` 会直接撞键。改为用 **`reply_to_msg_id`** 判定「本轮是否已生成」：命中则重放 `done`，不重复调用模型（实测重放 156ms、0 个 delta）。助手消息的 `client_msg_id` 留 NULL。
2. **`seq_no` 用 `COALESCE(MAX(seq_no),0)+1` 同事务分配**，不做 Redis 号段（Redis 已移除，且同用户单会话并发极低）。
3. **客户端中途断开不落库**。取消（cancel）不会触发完成回调，因此这一轮不写 `chat_message`，用户重试时重新生成完整回答——比先落半截、之后被幂等重放成残缺回答更合理。上游报错且已有部分内容时才落库并标 `finish_reason=4`。
4. **审计字段显式写入**：落库发生在 Reactor 线程（日志里是 `oundedElastic-1`），`RequestContext`（ThreadLocal）取不到用户，若依赖 `AuditMetaObjectHandler` 会把 `created_by` 写成 0。故显式 `setCreatedBy/setUpdatedBy(userId)`。

## 13.4 验证结果（真实调用，`--spring.profiles.active=dev,local`）

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 2.23 获取或创建会话 | ✅ 首次创建返回 `id=1`，再次调用返回同一会话 |
| 2 | 2.24 发送消息 | ✅ `{id:1, seqNo:1}` |
| 3 | 2.24 幂等 | ✅ 同 `clientMsgId` 重复提交返回同一 `id`、`seqNo` 不变；DB 只有 1 行 |
| 4 | 越权 | ✅ 另一用户访问该会话 → 404 + `40400`；无 Token → 401 |
| 5 | 2.25 SSE | ✅ `:connected` 注释 + 435 个 `delta` + `done`；响应头 `Content-Type: text/event-stream` |
| 6 | `done` 完整性 | ✅ 带 `assistantMsgId`、完整 `contentText`、`finishReason=1`、`modelCode`、tokens、`latencyMs`、`replyToMsgId` |
| 7 | 多轮上下文 | ✅ 第二轮问「主食具体吃多少克」，模型回答"我按上面说的思路…"；`promptTokens` 614 → 1070 证明历史已入上下文 |
| 8 | 落库字段 | ✅ `chat_message`：`seq_no` 连续、`reply_to_msg_id` 正确、`model_code=deepseek-flash`、tokens、`finish_reason=1`、`latency_ms`；助手行 `client_msg_id=NULL`、`created_by=2` |
| 9 | 会话冗余列 | ✅ `title` 取首条消息前 20 字（`title_len=18`）、`msg_count` 递增、`last_msg_at` 刷新、`system_prompt_ver=health_agent_system.md` |
| 10 | 客户端中途断开 | ✅ `curl --max-time 2` 中断后 `chat_message` 无新增助手行（符合 13.3-3 的预期） |
| 11 | 日志洁净 | ✅ 无 `Access Denied`、无 `ERROR`、无 SQL 正文泄漏 |

## 13.5 本轮修掉的一个真实缺陷：SSE 完成后的 ASYNC 派发被 Security 拒

**现象**：每次 SSE 正常结束后，日志都会刷两段 `AuthorizationDeniedException: Access Denied` 栈，并伴随 `Unable to handle the Spring Security Exception because the response is already committed`。客户端功能正常，但日志被污染、且掩盖真实异常。

**根因**：`emitter.complete()` 后容器会对同一请求再做一次 **ASYNC 派发**；自定义的 `UserSessionAuthFilter` 继承 `OncePerRequestFilter`，其 `shouldNotFilterAsyncDispatch()` 默认返回 `true`（跳过异步派发），而 `AuthorizationFilter` 是普通过滤器、照样执行，于是拿不到认证 → 拒绝。

**修复**（`auth/security/SecurityConfig`）：

```java
.authorizeHttpRequests(auth -> auth
        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
        ...)
```

修复后重跑整条链路，日志零告警零异常。**任何后续新增的 SSE/异步接口都会踩这个坑，已在代码里加注释说明。**

## 13.6 成本观察（给 L2 的输入）

单轮 `prompt_tokens` 随会话增长：614 → 1070 → 1562 → 1636。其中约 600 是固定的 930 字系统提示词，其余是历史原文。L2 做滚动摘要（`context_summary`）与窗口裁剪时，这是最直接的优化对象。

---

# 14. 小程序端接入记录（2026-09-17）

## 14.1 改动文件

| 文件 | 说明 |
|---|---|
| `frontend/utils/sse.js`（新增） | SSE 客户端：`wx.request({ enableChunked: true })` + `onChunkReceived` |
| `frontend/services/api.js` | 新增 `getOrCreateChatSession` / `sendChatMessage` / `streamChatCompletion` |
| `frontend/pages/voice/voice.js` | 由 `setTimeout` + 硬编码回复改为真实链路 |
| `frontend/pages/voice/voice.wxml` / `.wxss` | 「正在思考…」占位、失败后的「重新生成」入口、回复中禁用发送 |
| `GlucoPilot-frontend/tools/sse-check/`（原单仓 `project/tools/sse-check/`，2026-10-13 拆仓后随前端仓） | SSE 解析自检脚本 + 真实响应夹具（见 14.4） |

## 14.2 对话页链路

```
发送 → 本地乐观插入用户气泡 + 空助手气泡（pending）
     → POST /chat-sessions/current（进页面时已预热，通常直接命中缓存）
     → POST /chat-sessions/{id}/messages（clientMsgId 幂等）
     → POST /chat-sessions/{id}/completions（SSE）
         delta → 合并到助手气泡（60ms 批量刷新，避免数百次 setData 卡真机）
         done  → 用 contentText 落定，解除输入锁
         error → 保留已流出内容并在气泡内追加提示 + 「重新生成」入口
```

## 14.3 关键实现点（不看会踩）

1. **必须按字节缓冲再解码 UTF-8**。chunk 边界会把一个汉字切成两半，`String.fromCharCode` 或「每个 chunk 独立解码」必然乱码。`utils/sse.js` 用 `decodeUtf8()` 返回 `rest`（结尾不完整序列）留到下一块拼接。
2. **不能假设「一个 chunk = 一个事件」**。要按空行切事件，并把半截事件留在缓冲区。
3. **`delta` 要合并刷新**。一次回复推了 93~435 个 `delta`，逐个 `setData` 在真机上会明显掉帧；页面侧用 60ms 定时批量提交。
4. **重试复用 `clientMsgId` / `replyToMsgId`**，而不是重发一条新消息。服务端按 `reply_to_msg_id` 幂等：已生成过就直接重放 `done`，不会重复扣费也不会多出一条用户消息。
5. **开流前的校验失败返回的是统一 JSON（HTTP 4xx）而不是 SSE**。客户端必须能识别这种情况，否则页面会卡在「回复中」——`openSse` 在 `success` 里兜底解析 JSON 错误体并转成 `onError`。
6. **40100 统一跳登录**：对话页与其它页面一致，清 token 后 `reLaunch` 到登录页。
7. **图片/语音先不做假**。后端 `media`（2.14/2.15）与 ASR 尚未实现，点图片、按住说话统一提示「暂未开放」，不再返回硬编码假回复。

## 14.4 验证方式与结果

小程序端无法在 CI 里跑，但 SSE 解析是**最容易静默出错**的一段，因此把后端真实响应抓成夹具，在 Node 里把 `openSse` 的分片按任意粒度重放（`GlucoPilot-frontend/tools/sse-check/`，用法见其中 README）：

| 用例 | 断言 | 结果 |
|---|---|---|
| `sse_raw.bin`，chunkSize = 1 / 3 / 7 / 997 | `delta` 拼接结果 === `done.contentText`；事件数 93；无 `U+FFFD` | ✅ 全通过 |
| `err404.bin`（越权会话） | `onError` 触发、`code=40400`、message 原样透出、不产生业务事件 | ✅ |
| `err401.bin`（未登录） | `onError` 触发、`code=40100` | ✅ |
| 模块加载 | `services/api.js` 与 `utils/sse.js` 无循环依赖、导出齐全 | ✅ |

`chunkSize = 1` 是最严苛的用例：每个汉字都被拆到两个 chunk 里，仍然零乱码、零丢事件。

## 14.5 联调前需要人工确认的一步

`frontend/utils/config.js` 的 `baseUrl` 仍是既有的局域网地址 `http://192.168.101.147:8080/api/v1`：

- 开发者工具本机联调：改成 `http://127.0.0.1:8080/api/v1`，并在「详情 → 本地设置」勾选**不校验合法域名**（`project.config.json` 里 `urlCheck: true`）；
- 真机联调：保持电脑局域网 IP，同样关闭域名校验（详见 `frontend/docs/联调说明.md`）。

---

# 15. Spring AI 2.0 结构改造记录（2026-09-23）

## 15.1 这次改造要解决什么

L1 时期的实现把 Spring AI 当成「一个带流式 API 的 HTTP 客户端封装」在用：框架只用到
`ChatClient.Builder` 自动装配、`ChatClient.stream()`、DeepSeek 的 yaml 绑定三处，
其余（提示词加载、上下文装配、流状态聚合、token 采集）全部自研。
结果是框架里最有价值的三层——**Advisor 体系、记忆扩展点、工具调用**——完全空着，
而 AI 模块后续的 OCR / 饮食分析 / 长期记忆 / 后台配置全都要落在这三层上。

本次改造的目标：**按 Spring AI 2.0 的组织方式重排 AI 模块结构**，让框架承担模型编排，
我们只保留框架管不了的部分。**对外契约零变更**（2.23 / 2.24 / 2.25 的路径、事件名、字段一律不动），
因此前端无需改动。

## 15.2 新的模块结构

```
com.threadfit.glucopilot.ai
├── core/                          场景与模型路由（本次新增）
│   ├── SceneCode                  场景枚举：CHAT / CHAT_IMAGE / MEAL_ANALYSIS / OCR_GLUCOSE
│   ├── SceneModelResolver         场景 → 模型编码 / 请求级参数（取代 AiModelInfo）
│   └── AiChatClientRegistry       场景 → ChatClient（多 provider 的唯一改动点）
├── config/                        Spring AI 装配
│   ├── AIProperties               ai.* 业务编排配置（场景/记忆/安全词/工具白名单）
│   ├── AiChatClientConfig         ChatClient 装配：提示词 + Advisor 链 + 工具白名单
│   └── AiChatMemoryConfig         ChatMemory 装配（MessageWindowChatMemory + MySQL 存储）
├── prompt/
│   └── AiPromptTemplateService    提示词模板：Spring AI PromptTemplate + StTemplateRenderer
├── memory/
│   ├── MyBatisChatMemoryRepository  ChatMemoryRepository 的 MySQL 实现（落 chat_message）
│   └── ChatMemoryReadAdvisor        只读记忆注入 Advisor（不写记忆）
├── tool/
│   └── AiToolRegistry             工具白名单注册点（当前白名单为空）
├── support/
│   └── SqlDuplicateKeys           唯一键冲突识别（补回缺失的 SqlDuplicateHelper 能力）
└── business/
    ├── chat/{controller,dto,entity,mapper,service}   对话场景（接口 2.23 / 2.24 / 2.25）
    ├── meal/package-info.java                        饮食分析场景（2.20，待视觉模型接入）
    └── ocr/package-info.java                         血糖识别场景（2.21 / 2.22，待视觉模型与 media）
```

**为什么按「场景」而不是按「模型能力」分层**：场景的调用契约本来就不同——对话是
SSE 流式 + 多轮记忆 + 工具；饮食分析是同步的结构化 JSON；OCR 是异步任务 + 轮询 + 用户确认。
「用哪个模型 / 哪套参数 / 哪份提示词」是场景的属性，因此以场景为唯一入口，
四个场景一一对应 yaml 的 `ai.scenes.<key>`、提示词文件、以及后续 `ai_prompt_template.scene_code`。

## 15.3 关键决策（含理由）

| # | 决策 | 理由 |
| --- | --- | --- |
| 1 | **场景路由**（`SceneCode` + `SceneModelResolver` + `AiChatClientRegistry`） | 接第二个 provider（多模态）时只改 `AiChatClientRegistry.client()` 一处；视觉场景在 provider 未接入前**直接抛业务异常**，不允许回落到文本模型「假装看懂图片」 |
| 2 | **用 `ChatMemory` 取代自研 `ChatContextAssembler`** | 窗口裁剪交给框架的 `MessageWindowChatMemory`；存储只需实现 `ChatMemoryRepository` 的 4 个方法（2.0.1 不提供 JDBC 版）。旧类已删除 |
| 3 | **不挂框架的 `MessageChatMemoryAdvisor`**，改用自研只读 Advisor | 它会**写**记忆（调用 `ChatMemory.add`），与「助手消息必须等流结束拿到完整正文 + token 后才落库」冲突，会写出重复/残缺数据并让 `reply_to_msg_id` 幂等重放失效。写入仍由 `ChatCompletionService` 显式完成 |
| 4 | **提示词改为 `PromptTemplate` + 场景目录** | 取代自研 `PromotesLoader`/`SystemPromptProvider`；变量渲染用框架能力，为「后台表驱动提示词」（4.11~4.13）留好落点。**保留「空/缺失即启动失败」的 fail-fast 语义**，并新增「按声明变量试渲染一次」，把模板里的花括号错误挡在启动阶段 |
| 5 | **医疗安全用 `SafeGuardAdvisor` 做硬拦截** | 提示词里的合规约束是软约束（模型可能不遵守）；命中敏感词的请求**不发给模型**，直接返回固定话术（引导就医 + 免责声明）。可通过 `ai.guard.enabled` 关闭 |
| 6 | **工具只走白名单，绝不包扫描** | 提示词注入诱导越权工具调用是已知风险；调用次数上限交给框架的 `spring.ai.tools.limits.*`（默认超限即抛）。当前白名单为空——领域模块未实现，没有数据可查 |
| 7 | **`ai.*` 与 `spring.ai.*` 分工** | `spring.ai.*` 管 provider 与框架行为；`ai.*` 管业务编排（场景/记忆窗口/安全词/工具白名单）。场景里的参数是**可选覆盖**，不写就继承全局，避免同一份参数维护两处 |
| 8 | **显式收紧重试** | `DeepSeekChatModel` 内部持有 `RetryTemplate`（真的会重试），框架默认 `max-attempts=10`、退避 2s × 5 倍；与 60s 的 SSE 超时叠加会让用户长时间干等后拿到 error。已改为 3 次 / 1s × 2 倍 |
| 9 | **SSE 桥接保持自研** | 框架出口是 `Flux<String>`，没有 `SseEmitter` 桥接；且 L1 已经把 ASYNC 派发被 Security 拒、Nginx 缓冲、断开取消、ThreadLocal 这些坑踩完并修好。为「更框架」重写这一层是净风险 |

## 15.4 文件级改动清单

| 动作 | 文件 |
| --- | --- |
| 新增 | `core/{SceneCode,SceneModelResolver,AiChatClientRegistry}`、`prompt/AiPromptTemplateService`、`memory/{MyBatisChatMemoryRepository,ChatMemoryReadAdvisor}`、`tool/AiToolRegistry`、`support/SqlDuplicateKeys`、`config/{AiChatClientConfig,AiChatMemoryConfig}` |
| 新增 | 提示词 `resources/prompts/chat/system.md`（= 原 `health_agent_system.md` 正文，内容未改）、`prompts/meal-analysis/system.md`、`prompts/ocr-glucose/system.md` |
| 新增 | 单测 `ai/config/AiPropertiesBindingTest`、`ai/memory/ChatMemoryReadAdvisorTest`、`ai/prompt/AiPromptTemplateServiceTest`、`ai/support/SqlDuplicateKeysTest` |
| 改造 | `config/AIProperties`（场景/记忆/安全词/工具段）、`business/chat/service/{ChatCompletionService,ChatSessionService,ChatMessageService}`、`business/chat/mapper/ChatMessageMapper`（新增按会话取最近消息、逻辑删除）、`application.yaml` |
| 删除 | `config/{ChatClientConfig,SystemPromptProvider,AiModelInfo}`、`utils/PromotesLoader`（整包）、`business/chat/context/ChatContextAssembler`、`business/chat/controller/ChatSmokeController`、`business/chat/service/ChatSmokeService`、`business/chat/dto/ChatSmokeDtos`、`resources/promotes/`（含两个 0 字节文件） |
| 删除 | 无对应接口的空占位包：`business/{food,gateway,image,recommendation,task}`、`business/chat/{context,message,session,speech}`；`business/ocr` 保留并补 `package-info.java` 说明待办 |

> 顺带修掉一个**既有编译错误**：`ChatMessageService` 引用的
> `common.util.SqlDuplicateHelper` 在当前分支已不存在，AI 模块一度**编译不过**。
> 按模块边界，该能力先落在 `ai/support/SqlDuplicateKeys`；若 `common` 侧恢复通用工具可直接切换。

## 15.5 行为变更（对外契约不变，但行为有差异，前端无需改）

| # | 变更 | 说明 |
| --- | --- | --- |
| 1 | `chat_session.system_prompt_ver` 语义 | 由「文件名」改为「版本标签」，如 `chat@v1`（`ai.scenes.chat.version`） |
| 2 | 会话窗口来源 | 由硬编码 20 条改为 `ai.memory.window-messages`（默认仍 20） |
| 3 | 历史去重 | 本轮用户消息在开流前已落库，记忆注入时会去掉历史末尾的重复项（见 `ChatMemoryReadAdvisorTest`） |
| 4 | 图片/语音消息 | 2.24 仍接受 `msgType=2/3` 并落库，但对这类消息请求生成回复会明确返回 `40001`（不把空串送给模型）；原实现的提示语较模糊，现在指明「待多模态/ASR 接入」 |
| 5 | 医疗安全硬拦截 | 命中 `ai.guard.sensitive-words` 的提问由固定话术回复，不再调用模型 |
| 6 | 重试次数 | 由框架默认 10 次收紧为 3 次 |
| 7 | 启动校验 | 四个场景的提示词必须齐全且可渲染，否则**启动失败**（场景枚举、yaml、提示词文件必须同批落地） |

## 15.6 已核实的框架事实（`javap` 逐个验证，避免再被文档误导）

1. `DeepSeekChatProperties` 前缀 = `spring.ai.deepseek.chat`，`model`/`thinking`/`max-tokens` 是其自身字段；`chat.model` 与 `chat.options.model` **都能绑定**（共 25 个属性）——修正了 §3.2。
2. `DeepSeekApi$ChatModel` 有四个值，含 `deepseek-v4-flash` / `deepseek-v4-pro`——修正了 §3.3。
3. `@ConditionalOnProperty(name="spring.ai.model.chat", havingValue="deepseek", **matchIfMissing=true**)`：**一次只激活一个聊天 provider**。引入第二个 provider 后必须显式写 `spring.ai.model.chat`，否则两家争抢 Bean。
4. `ChatClientAutoConfiguration.chatClientBuilder(..., **ChatModel**, ...)` 注入的是**单个** `ChatModel`：容器里出现第二个 `ChatModel` 就会注入歧义，需要 `@Primary` 或关掉自动装配。**并且工具/观测只存在于自动装配的 Builder 上**——手搓 `ChatClient.builder(model)` 会静默丢掉工具（已在代码注释里标注）。
5. `ChatMemoryAutoConfiguration` 自带 `ChatMemoryRepository` 与 `ChatMemory`，均为 `@ConditionalOnMissingBean` → 提供自己的 Repository 即可接管；2.0.1 **没有** JDBC 版记忆仓储。
6. `MessageChatMemoryAdvisor` 会写记忆（`ChatMemory.add`）→ 与本项目落库时序冲突。
7. `BaseAdvisor.DEFAULT_SCHEDULER`：Advisor 回调不在请求线程上，`RequestContext`（ThreadLocal）在 Advisor 里同样取不到用户 —— 与已有的 Reactor 线程坑同源。
8. `spring.ai.retry.*` 默认 `max-attempts=10`、退避 2s × 5 倍、`on-client-errors=false`。
9. **框架没有模型级 HTTP 超时属性**（DeepSeek 25 个属性里没有 timeout），而 `ai_model_config.timeout_ms` 这一列已预留 → 超时目前仍靠 SSE 60s 兜底，是**已知缺口**。
10. `DeepSeekApi$ChatCompletionMessage.content` 是 `String` → **DeepSeek 不支持图片输入**；`Media`/`MediaContent` 只在 `spring-ai-commons`，DeepSeek 模型接不了。**多模态必须第二个 provider。**
11. 依赖里**没有** `VectorStore` 实现、没有 `QuestionAnswerAdvisor`、没有 `SimpleVectorStore` → RAG 需要额外 starter + 选向量库，不是「配置一下就有」。
12. `ChatClient.Builder` 的三个 `defaultToolCallbacks(...)` 重载**全部已过时**，替代品是 `defaultTools(Object...)`。
13. `StTemplateRenderer` 在 `spring-ai-template-st`，而它是 `spring-ai-model` 的传递依赖——**无需新增依赖**。

## 15.7 验证方式与结果

```bash
cd backend
mvn -DskipTests compile     # BUILD SUCCESS（151 个源文件）
mvn test                    # Tests run: 22, Failures: 0, Errors: 0
```

覆盖的四个单测针对的都是「最容易静默出错」的地方：yaml 与配置类的绑定（含四个场景与提示词
文件齐备性，等价于不启容器跑一次启动前校验）、记忆注入去重、提示词模板校验、唯一键冲突识别。
**没有跑真实模型调用**（本机无 Key、无远程 MySQL），因此
「Spring 容器装配是否全部正确」仍需一次 `--spring.profiles.active=dev,local` 的真实启动来确认。

## 15.8 遗留与待办

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | **工具装配未端到端验证** | `ai.tools.allow-list` 为空，无工具可验。实现第一个真实工具时，必须先用「假 ChatModel 返回 `tool_calls` 回合」的测试确认工具真的被调用（本轮尝试过，未能在纯单测里构造出工具循环，已如实记录） |
| 2 | 模型级 HTTP 超时缺失 | 需自定义 `RestClient`/`WebClient` 或等 Boot 的 `spring.http.client.*`；注意别用全局 Bean 影响其它模块 |
| 3 | `chat_context` / `context_summary` 仍未使用 | 上下文分片审计与滚动摘要（token 预算）尚未实现，`prompt_tokens` 会随会话持续增长 |
| 4 | 无任何限流/配额 | Redis 已移除，需进程内 Caffeine/Semaphore；`ErrorCode.TOO_MANY_REQUESTS`(42901) 已存在，无需新增错误码 |
| 5 | ~~Flyway 版本冲突（跨模块）~~ **已于 2026-09-28 修复** | `V4__ruoyi_full_system.sql` 与 `V4__widen_user_agent.sql` 同版本号，实测报错 `Found more than one migration with version 4`，应用完全无法启动。修复方式与依据见 §16 第 2 条 |
| 6 | 生产缺 AI Key 注入 | `.env.example` 无 `DEEPSEEK_*`，也没有 `application-prod.yaml`，而 compose 用 `SPRING_PROFILES_ACTIVE=prod` |
| 7 | 契约待补 | 5.3 会话列表 / 5.4 删除会话 / 历史消息接口（文档缺）尚未实现，`ChatMemoryRepository.deleteByConversationId` 已为其备好 |

## 15.9 本次未做的事（守住边界）

- 未改前端（`frontend/`、`admin/`）与任何接口契约；
- 未引入第二个 provider（多模态）——路由抽象已就位，接入时只改 `AiChatClientRegistry`；
- 未改 `SseEmitter` 桥接、幂等、`seq_no`、落库字段、鉴权与越权语义；
- 未引入 WebFlux、未引入 VectorStore/RAG；
- 未动 Flyway 迁移、`.env.example`、`chat/smoke` 之外的基础设施（见 §16 待确认）。

---

# 16. 待确认清单（需决策后动）

| # | 问题 | 影响 |
| --- | --- | --- |
| 1 | ~~模型名用 `deepseek-flash` 还是 `deepseek-v4-flash`？~~ **已决策（2026-09-23）：改用官方枚举名 `deepseek-v4-flash`** | 待第一次真实调用确认可用性与时延/token 无异常 |
| 2 | ~~Flyway 的 V4 撞车怎么处理？~~ **已修复（2026-09-28）** | 实测报错 `Found more than one migration with version 4`。数据库 `flyway_schema_history` 显示 `version 4 = V4__widen_user_agent.sql`（success=1，且该库是 baseline 到 3 的），故**保留它不动**，把 `V4__ruoyi_full_system.sql` 改名为 **`V7__ruoyi_full_system.sql`**（V5/V6 当天已被其他改动占用）。⚠️ 改名后必须 `mvn clean compile`——报错指向的 `target/classes/db/migration` 是资源副本，不清旧文件会继续报同样的错。该文件涉及 RuoYi 合并提交，已按需知会。<br>⚠️ **后续（2026-09-29）：这个 V7 又撞了车**——两条分叉分支合并后出现 `V7__alter_media_file_storage_provider.sql`，应用再次起不来。已按 `flyway_schema_history` 的证据把未应用的那个改为 `V8`（详见 §19.4）。**同一个问题发生两次，已补 `MigrationFilesTest` 防呆** |
| 3 | 生产 AI Key 如何注入（`.env.example` 补 `DEEPSEEK_API_KEY` + 新增 `application-prod.yaml`）？ | 决定 AI 链路在生产能否起来 |
| 4 | 是否现在补进程内限流（`AiCallGuard`）与单用户 token 配额？ | 当前任意登录用户可无限调用大模型 |
| 5 | ~~多模态 provider 选型（qwen-vl / 豆包 / glm-4v …）与是否引入 `spring-ai-starter-model-openai:2.0.1`~~ **已决策（2026-09-29）：阿里云百炼 qwen-vl；引 `spring-ai-openai` 库而非 starter**（理由见 §19.3 第 2 条） | CHAT_IMAGE 已落地，见 §19；MEAL_ANALYSIS / OCR_GLUCOSE 待排期 |
| 6 | 血糖仪读数用专用 OCR 还是通用视觉模型？ | 医疗数据准确性优先，建议专用 OCR 优先、VLM 兜底 |

---

# 17. 接口 2.25 改为返回 Flux（2026-09-24）

## 17.1 改了什么

§15 那轮改造把 SSE 桥接保留为自研；本轮按「让框架接管 HTTP 侧」的方向再进一步：
**2.25 不再手写 `SseEmitter`，控制器直接返回 `Flux<ServerSentEvent<byte[]>>`**，
由 MVC 的 `ReactiveTypeHandler`（内部 `SseEmitterSubscriber`）完成写出、超时与取消。

| 动作 | 文件 | 内容 |
| --- | --- | --- |
| 改造 | `ChatCompletionService` | 返回类型 `SseEmitter` → `Flux<ServerSentEvent<byte[]>>`；删除 `emitter`/`AtomicReference<Disposable>`/`dispose`/`sendComment`/`sendFailed`/`SSE_TIMEOUT_MS`；落库改由 `concatWith(Mono.fromCallable(...))` 表达 |
| 改造 | `ChatController` | 2.25 返回 `Flux<ServerSentEvent<byte[]>>`（其余两接口不变，仍返回 `ApiResponse`） |
| 改造 | `application.yaml` | 新增 `spring.mvc.async.request-timeout: 60s`（超时口径从代码搬到配置，见 17.3） |
| 新增 | `ChatCompletionServiceTest` | 6 条收尾语义用例（正常/上游报错/取消/usage-only/幂等重放/落库失败） |
| 新增 | `ChatControllerSseWireTest` | 报文形态用例 + charset 根因用例 + 开流前失败同步抛出用例 |

## 17.2 踩到的真坑：中文被写成 `?`

第一次跑测试，框架写出的报文是：

```
event:delta
data:{"delta":"??"}
```

**根因**：`SseEmitterSubscriber.adapt()` 把 `ServerSentEvent` 转成
`SseEmitter.event().name(...).data(payload)`，而这个 `data(payload)` **不带 media type**，
于是框架按响应内容类型 `text/event-stream` 去选转换器：

| 载荷类型 | 选中的转换器 | 结果 |
| --- | --- | --- |
| POJO | Jackson 只支持 `application/json` | 序列化失败 |
| `String` | `StringHttpMessageConverter` | charset 取决于响应内容类型，**中文变 `?`** |
| `byte[]` | `ByteArrayHttpMessageConverter`（支持 `*/*`） | **原样输出字节，不经过 charset 判断** ✅ |

**试过的弯路（记录以免后人重走）**：给 `produces` 加 `;charset=UTF-8` —— **无效**。
实测响应内容类型仍是 `text/event-stream`（不带 charset），charset 不会进响应头。
所以最终方案是把载荷发成 **UTF-8 原始字节**，字节与原先
`SseEmitter + MediaType.APPLICATION_JSON`（Jackson）的产物一致。
`ChatControllerSseWireTest.不带charset时String载荷会把中文打成问号` 把这条机理钉住了。

> 顺带说明：原来的 `SseEmitter` 实现因为显式传了 `MediaType.APPLICATION_JSON` 走 Jackson，
> 反而没有这个问题——**换成"更框架"的写法才暴露出来**，这也说明这类改造必须带报文级测试。

## 17.3 两处有意的行为变更

| # | 变更 | 说明 |
| --- | --- | --- |
| 1 | **去掉 `:connected` 开流注释行** | `ServerSentEvent` 表达不了纯注释：`adapt()` 总会调用 `.data(...)`，传 `null` 不可行，传空串会产出一个顺序不确定的多余空事件。因此开流第一个字节改为第一个 `delta` 到达时（首字约 2~3s）。前端不依赖该注释行（`utils/sse.js` 明确跳过注释行，页面另有「正在思考…」占位） |
| 2 | **超时口径改为配置** | 原来写死在 `new SseEmitter(60_000)`；现在由 `spring.mvc.async.request-timeout: 60s` 决定。**不配就落到容器默认值（Tomcat 通常 30s）**，等于悄悄把 60s 改小——所以这个配置项是本次改动的一部分，不是可选优化 |

## 17.4 语义等价与两处改善

| 场景 | 行为 | 与 §15 版本是否等价 |
| --- | --- | --- |
| 正常结束 | 落库 + `done` | 等价 |
| 上游报错且已有内容 | 部分内容落库（`finish_reason=4`）+ `error` 事件 | 等价 |
| **客户端断开 / 超时** | 订阅被取消 → `concatWith(done)` 不执行 → **不落库** | 等价；且从「靠 4 处 `dispose` 兜住」变为**结构上保证** |
| **落库自身失败** | 只尝试一次，不再由 error 分支重复落库 | ✅ 改善（新增 `state.persisted` 守卫） |
| **模型名回落到配置值** | 上游元数据里 model 为空串时回落到 `SceneCode.CHAT` 的配置值 | ✅ 改善（原实现用 `!= null` 判断，空串会被写进 `chat_message.model_code`） |

## 17.5 验证方式与结果

```bash
cd backend
mvn test     # Tests run: 31, Failures: 0, Errors: 0
```

- `ChatCompletionServiceTest`（6 条）：收尾语义逐条锁死，其中「取消不落库」用
  `Flux.concat(just(chunk), Flux.never())` + `take(1)` 模拟客户端断开／容器超时；
- `ChatControllerSseWireTest`（3 条）：报文经框架写出后中文与分帧正确、charset 根因、开流前失败同步抛出。

**测试写法上的一个坑（值得记录）**：裸 `MockMvc` + `asyncDispatch` 对 `SseEmitter` 的异步写出
**捕获不可靠**——实测只能读到半截报文（停在 `event:delta`），加轮询也没用。
改用 `MockMvcWebTestClient.bindTo(mockMvc)` 后能完整还原事件。这不是产品缺陷，是测试手段问题。

## 17.6 仍未做的事（与本次无关，但会影响线上观感）

- **Nginx 缓冲仍未处理**：`admin/nginx.conf` 没有 `proxy_buffering off`，Java 侧也没有
  `X-Accel-Buffering: no`。生产上可能出现「整段一次性吐出」（流式观感失效），
  且 `proxy_read_timeout 30s` 小于服务端 60s。这条与「用不用 Flux」无关。
- 未做真实环境 `curl -N` 复核：本机无 Key／无远程 MySQL，无法启动完整应用。
  建议第一次联调时按前端仓 `GlucoPilot-frontend/tools/sse-check/README` 重新抓一次夹具，顺便确认 17.2 的编码在真实链路上同样正确。

---

# 18. 医疗安全硬拦截误拦全体请求（2026-09-28，已修复）

## 18.1 现象

联调时输入「你好」「我叫小明」等完全无害的内容，**也直接返回固定话术**，
模型从未被调用——即每一条消息都被拦，与用户说什么无关。

## 18.2 根因：框架的 `SafeGuardAdvisor` 扫的是整个 Prompt

字节码（`SafeGuardAdvisor.lambda$containsSensitiveWord$0`）：

```java
request.prompt().getContents()          // ← 整个 Prompt 的全部文本
       .toLowerCase(Locale.ROOT)
       .contains(sensitiveWord.toLowerCase(Locale.ROOT))
```

而 `Prompt.getContents()`（同样是字节码确认）是**遍历 `getInstructions()` 拼接所有消息的文本**，
**包含 SystemMessage**。我们的系统提示词 `prompts/chat/system.md` 里写着：

> 1. 严禁给出诊断、**开药**、调整降糖药/胰岛素**剂量**；

「开药」「剂量」正好在 `ai.guard.sensitive-words` 词表里 → **系统提示词自己命中了词表** →
所有请求短路，模型不被调用。§15.3 决策 5 引入该 Advisor 时，误以为它只判用户输入。

值得注意的是：**框架这个版本不打印任何命中日志**，这也是问题「静默发生」的原因之一。

## 18.3 修法：自研护栏，只判「本轮用户消息」

新增 `ai/guard/MedicalGuardAdvisor` 替代 `SafeGuardAdvisor`，语义修正为
**只判用户问了什么，不判我们自己的提示词写了什么**：

| 点 | 做法 |
| --- | --- |
| 匹配对象 | Prompt 里**最后一条有文本的 UserMessage**（从末尾往前找，因此不依赖 advisor 顺序；即使将来记忆注入排到它前面，取到的仍是本轮消息） |
| 匹配方式 | `toLowerCase(Locale.ROOT).contains(...)`，与框架保持一致 |
| 命中行为 | 不调用下游链，直接返回 `ChatClientResponse`（一条 `AssistantMessage` 装固定话术）；流式同理返回单元素 `Flux` |
| 可观测性 | **命中记 WARN 日志**（含命中词），补上框架缺失的那一环 |
| 顺序 | `HIGHEST_PRECEDENCE + 100`，排最前：拦截不依赖后续 advisor，命中时连记忆查询都不必发生 |
| 配置 | 不变，仍由 `ai.guard.enabled` / `sensitive-words` / `failure-response` 驱动 |

## 18.4 回归测试（不可删）

`MedicalGuardAdvisorTest` 共 7 条，其中第一条就是本次事故的回归：

1. **真实提示词文件**作为 SystemMessage（并断言它确实含「剂量」「开药」）+ 用户消息「你好」→ **必须放行**；
2. 用户消息命中敏感词 → 拦截且 `verify(chain, never()).nextCall(any())`（模型确实没被调用）；
3. 历史里有敏感词、本轮没有 → 放行（只判本轮）；
4. 流式路径同样拦截；
5. 空词表不拦截；
6. 没有用户消息不拦截；
7. 大小写不敏感。

## 18.5 教训

- **「扫什么范围」必须读实现确认，不能按名字猜**：`SafeGuardAdvisor` 的名字让人以为它守护的是「用户输入」，
  实际守护的是「整个 Prompt」。这与 §15.6 里 `chat.model` 路径、`defaultTools` 是否接受 `ToolCallback`
  是同一类问题——**框架的语义要用字节码/源码核实**。
- **提示词与词表会互相污染**：一份讲用药安全的提示词 + 一份「提到用药就拦」的词表，本身就冲突。
  凡是"对文本做匹配"的功能，都要想清楚它匹配的是哪一段文本。
- **静默失败最贵**：框架版没有日志，所以只能靠用户反馈发现。现在命中会记 WARN。

## 19.1 本次做了什么：图片消息（接口 2.24 msgType=2）

对话框内发图片从「明确拒绝」变成可用。数据库与接口契约**都没有新增接口**：
`chat_message` 早就留好了 `msg_type=2` + `media_id`，2.24 也已经校验「图片消息 mediaId 必填」，
缺的只是「把图片变成模型能读的东西」这条链路。

### P0：先修一个越权读（必须先于多模态上线）

原 `validatePayload` 只校验 `mediaId` **非空**，不校验它**归谁**。
在只有文本模型的阶段，用户 A 传用户 B 的 `mediaId` 只会得到一条引用不到图的坏消息；
但接上视觉模型后，服务端会主动把 B 的图片读出来、描述给 A 看——**从坏引用升级为实质性的越权内容泄露**。
因此这一步排在接模型之前，与餐食模块 `MealRecordServiceImpl#resolveMediaId` 用同一个工具方法
`findOwnedMedia(mediaId, userId, bizType)`（一条 SQL 同时校验 userId + bizType + 可用状态）。
`msgType=3`（语音）有同一个洞，一并修掉。2.25 读图前会再校验一次。

## 19.2 两阶段：视觉模型识别 → 文本模型回答

`SceneCode.CHAT_IMAGE` 的定义本来就是「视觉模型先识别，再交给文本模型组织回答」，
本次按这个语义实现，因此 **`SceneCode`、`AiPromptTemplateService`、`SceneModelResolver` 一行都不用改**：

| 阶段 | 谁做 | client / 提示词 | 产出 |
| --- | --- | --- | --- |
| 阶段 1 | `VisionAnalyzer`（非流式） | `visionChatClient` + `prompts/chat-image/analyze.md` | 客观描述 |
| 阶段 2 | `ChatCompletionService`（流式） | `textChatClient` + `prompts/chat/system.md`（即 CHAT 场景） | 面向用户的回答 |

阶段 2 本质就是一次普通对话，所以复用 `CHAT` 场景的完整链路（医疗红线、会话记忆、Advisor 链）都在。

**为什么不做单阶段**（让视觉模型直接对用户说话）：那样它要同时承担「客观描述」和
「遵守医疗红线 + 引用会话上下文 + 维持人设」两类职责，而后者在文本链路上已经有完整实现，
复制一份到视觉链路等于维护两套合规约束。拆开之后职责清晰，而且**一次视觉调用同时服务两件事**——
描述既进阶段 2 的上下文，也回填进会话记忆。

## 19.3 关键决策（含理由）

1. **视觉 provider 选阿里云百炼 qwen-vl**（OpenAI 兼容协议）。理由：账号体系与 OSS 一致，
   后续 2.20 饮食分析、2.21 血糖识别可复用同一家。
2. **引 `spring-ai-openai` 库，不引 `spring-ai-starter-model-openai`**。starter 会带来第二套自动装配，
   而 `ChatClientAutoConfiguration` 是按**单个** `ChatModel` 注入的，上下文里出现第二个 `ChatModel` Bean
   会因候选不唯一直接启动失败。只借库里的 `OpenAiChatModel`，在 `AiVisionClientConfig` 里手工构造
   并立刻包成 `ChatClient`，容器里始终只有一个 `ChatModel`。
   （连带代价：两个 `ChatClient` Bean 必须补 `@Qualifier`，否则注入点歧义。）
3. **图片走 base64 而不是 URL**。开发环境的对象存储地址是 `localhost`，模型服务根本回连不到；
   生产即便公网可达，也等于把用户健康图片的地址暴露给第三方，且与本项目「桶私有」的契约冲突。
   为此给 `ObjectStorageService` 加了 `getObject`（服务端读取，不产生公开 URL）。
4. **图片描述落 `chat_message.media_caption`**（新增列，与语音的 `asr_text` 语义对称）。
   不能复用 `content_text`——那是前端气泡里显示的「用户原话」。
5. **描述在本轮结束后一次性回填**，不在阶段 2 之前。若提前回填，记忆装配会在图片消息上回放摘要，
   而阶段 2 的当前消息里又带着同一份描述，模型会连着看到两遍、白花 token。
   写在与助手消息落库同一个 `Mono` 回调里还带来一个好处：客户端取消/超时时该回调不执行，
   于是**取消的轮次也不会写摘要**，与既有的「取消轮次不落库」天然一致。
6. **阶段 1 同步执行**。它必须在开流前完成，代价是占用请求线程约 1~3 秒、首字延迟从约 2~3 秒
   升到约 4~6 秒。换来的是错误语义更干净：视觉阶段失败在**开流前**抛异常，
   客户端拿到标准 JSON 错误，而不是流到一半再收 `error` 事件。

## 19.4 踩到的坑（两个都很隐蔽）

### 图片进不了记忆，而且**两处**都会把它丢掉

纯图片消息的 `content_text` 为空，而记忆装配有两道门槛：
`ChatMessageMapper.selectRecentBySession` 的 **SQL 过滤**（`content_text IS NOT NULL AND <> ''`）
和 `toMessage` 的 **Java 判空**。
只改 Java 侧的话，摘要会写进库却依然回放不出来，表现为「明明存了，模型就是不记得这张图」。
两处都改，并用 `MyBatisChatMemoryRepositoryTest` 锁住。

### 迁移版本号撞车：仓库里同时存在两个 `V7`

`V7__ruoyi_full_system.sql` 与 `V7__alter_media_file_storage_provider.sql`，
Flyway 会直接报 `Found more than one migration with version 7`，**应用根本起不来**。

归属：两个提交（`729b96b` 媒体模块 / `3cc0a0a` 本次之前的 V4→V7 重命名）**在两条分叉线上**，
是后来合并到同一个 HEAD 才凑出两个 V7——双方各自都没错。

处理依据是**证据不是猜测**：查 `flyway_schema_history` 显示 `version 7 = V7__ruoyi_full_system.sql`（已应用），
`media_file.chk_mf_prov` 仍是 V1 的旧定义 `(1,2,3)`（证明媒体那个 V7 从未应用过）。
**已应用的不能动，未应用的让位** → 媒体那个改成 `V8`，本次新增迁移顺延为 `V9`。

新增 `MigrationFilesTest` 防呆（版本号唯一性）——这已是第二次被同类问题坑到（上次是 V4）。
另注意：`target/classes/db/migration` 是资源副本，**改完必须 `mvn clean`**，
否则 IDE 运行时读到的仍是旧副本（§15.6 记过这个坑，这次又验证了一遍）。

### 一个关于模板渲染的结论（省掉一层转义）

`StTemplateRenderer` 用的是 `ValidationMode.THROW`（缺变量立刻报错），
于是必须确认「用户自己打的文本」「视觉模型产出的描述」里出现花括号会不会被当成未声明变量。
`UserTextTemplateRenderingTest` 实测结论：**不会**——系统提示词走模板渲染，而 `.user(...)` 的文本原样透传。
所以阶段 2 可以直接拼接描述，不需要转义、也不需要绕 `param()` 传值。
（写这组用例时还踩了个小坑：`CallResponseSpec.call()` 只是返回规格对象、**不触发模型调用**，
真正发起调用的是 `.content()`，漏了它断言只能拿到 null。）

## 19.5 文件级改动清单

新增：`ai/vision/VisionAnalyzer.java`、`ai/config/AiVisionClientConfig.java`、
`resources/prompts/chat-image/analyze.md`、`db/migration/V9__add_chat_message_media_caption.sql`；
测试 `AiChatClientRegistryTest`、`ChatMessageServiceTest`、`MigrationFilesTest`、
`UserTextTemplateRenderingTest`、`MyBatisChatMemoryRepositoryTest`。

改动：`pom.xml`（+`spring-ai-openai`）、`AIProperties`（+`ai.vision.*`）、`AiChatClientRegistry`（分流 +
`@Qualifier`）、`AiChatClientConfig`（修正一句已过时的注释）、`ChatMessageService`（归属校验）、
`ChatCompletionService`（两阶段 + 摘要回填）、`ChatMessageMapper`（记忆 SQL + `updateMediaCaption`）、
`MyBatisChatMemoryRepository`（摘要兜底）、`ChatMessage`（+`mediaCaption`）、
`ObjectStorageService` 及 MinIO/OSS 两实现（+`getObject`）、`application.yaml`（`chat-image` 提示词指针 + `ai.vision.*`）。

## 19.6 验证方式与结果

- **单测 124 个全绿**（本次新增 11 个）。
- **`getObject` 对真实 MinIO 验证过**：直接 `new` 编译产物里的 `MinioObjectStorageService`，
  跑「凭证 → 直传 → 读回」，53 字节字节完全一致、中文原样。
- **装配验证**：`AiChatClientRegistryTest` 覆盖「有/无视觉 client」两种装配，
  漏 `@Qualifier` 会在启动期炸。
- ⚠️ **未做真实视觉调用联调**：需要百炼 API Key（只放 `application-local.yaml`，已 gitignore）。

## 19.7 已知代价与遗留

1. **图片轮次绕过文本硬拦截**。`MedicalGuardAdvisor` 只扫最后一条 `UserMessage` 的文本，
   纯图片消息文本为空 → 直接放行。风险具体：一张血糖仪显示 2.1 mmol/L 的照片不会被硬拦。
   当前依赖阶段 2 的 CHAT 提示词（含医疗红线）做软约束。**这是已知缺口，需要时再加输出后置检查。**
2. **首字延迟约 4~6 秒**（原约 2~3 秒），见 §19.3 第 6 条。
3. **客户端在阶段 1 期间断开时，这次视觉调用已经花掉**（但不会落库）。
4. **前端未做**：`frontend/pages/voice/voice` 还只有文本输入，图片选择 → 2.14 → 直传 → 2.15 → 2.24 的链路待接。
5. **视觉 client 没有工具能力**（有意为之，见 `AiVisionClientConfig` 类注释）；图片场景当前不需要工具。

---

# 20. 多模态收尾：后端语义对齐 + 前端接入（2026-09-29）

§19 落地后发现**代码结构与「两阶段」这个事实不一致**，本节记录对齐过程。根因是同一个：
`SceneCode` 的抽象前提是「一个场景 = 一个 client、一套参数、一个模型」，
而两阶段打破了这个前提（`CHAT_IMAGE` 需要两个 client、两个模型）。

## 20.1 四处真实的冲突（不是洁癖，是三类会出事的形态）

| # | 问题 | 后果 |
| --- | --- | --- |
| 1 | `client(SceneCode)` 语义被两阶段搞混 | 传 `CHAT_IMAGE` 得到视觉 client，但该场景最终对用户说的话出自文本 client——读代码时必然误判 |
| 2 | `modelCode(视觉场景)` 回落成 `spring.ai.deepseek.chat.model` | 「落库的模型 = 实际调用的模型」这条承诺对视觉场景失效，成本核算会算到从没被调用过的模型上 |
| 3 | `optionsBuilder(视觉场景)` 返回 `DeepSeekChatOptions` | 视觉 client 是 OpenAI 协议，把 DeepSeek 的 options 交给它是**类型层面的错误**——三个里唯一真会炸的 |
| 4 | 媒体归属校验写了两遍（2.24 与 2.25） | 文案已不一致；更危险的是将来越权规则只改一处，会留下「发消息被拒、但直接调补全却读得到他人图」的半修状态 |

## 20.2 修法

1. **`AiChatClientRegistry` 按「能力」而不是「场景」暴露**：`textClient()` / `visionClient(SceneCode)`。
   场景参数在 `visionClient` 里只作**断言**（传纯文本场景进来立刻 `IllegalArgumentException`），
   而不是用来选 client——静默返回一个 client 会让「图片被送给纯文本模型」这种事无声无息。
2. **`SceneModelResolver` 三个方法对视觉场景区别对待**：`modelCode` 返回 `ai.vision.model`；
   `hasOverrides` 恒为 `false`；`optionsBuilder` 直接拒绝并说明去改 `ai.vision.*`。
3. **新增 `ai/support/OwnedMediaResolver`**：媒体归属规则的唯一实现（查询 + 文案），
   2.24 与 2.25 共用。拒绝行为的断言集中到 `OwnedMediaResolverTest`，
   `ChatMessageServiceTest` 只负责「业务类型传对了没、校验失败有没有阻止落库」。
   这样将来改规则只需改一处、测一处。

## 20.3 前端接入（`frontend/`）

`pages/voice/voice` 原本只是把 `onChooseImage` 弹一个「暂未开放」，但 WXML 里的图片气泡
（`item.type === 'image'` + `imagePath`）和 `onPreviewImage` 早就留好了——属于"留了口子没实现"。

- 新增 `utils/media.js`：三步上传（凭证 → 直传 → 登记）的编排，两个必须绕开的坑写在文件头注释里：
  1. **直传不能复用 `utils/request`**——那一步打的是对象存储的预签名 URL，返回裸 2xx（空 body）、
     没有 `ApiResponse` 信封，而 `request()` 要求 `body.code === 0`，会把成功判成失败；
  2. **不能用 `wx.uploadFile`**——它只支持 POST + multipart，而后端签出的是 **PUT**，
     因此用 `wx.request` 直接发 ArrayBuffer。
- `services/api.js` 补 2.14 / 2.15 两个接口，保持 endpoint 集中在一处。
- `pages/voice/voice.js`：`onChooseImage` 改用 `wx.chooseMedia`（压缩后取图），
  并把「文本轮次」和「图片轮次」统一到 `appendRound` + `startRound(payload, ...)` 一条链路，
  因此 SSE、幂等、重试语义完全共用；重试会区分「消息已入库」与「没写成功」，
  后者对图片还会**从本地临时路径重走上传**，不要求用户重新选图。
- 选图时输入框里已有的文字会作为 `contentText` 一起发送（后端支持「图 + 文字」），
  这正是阶段 1 的识别线索来源。

### 两个刻意的选择

- **按文件魔数判断真实 MIME，而不是信扩展名**：小程序临时文件后缀由拍摄/相册来源决定，
  可能与真实格式不符；这个 MIME 会随 2.15 落库、再作为 data URI 类型送给视觉模型，传错会让模型按错误格式解析。
- **直传时不设置 `Content-Type`**：预签名 URL 的签名范围只含 host（实测 `X-Amz-SignedHeaders=host`），
  但阿里云 OSS 的 V1 签名会把 `Content-Type` 计入待签串，签名时为空、上传时非空就会 `SignatureDoesNotMatch`。
  真正对业务有用的 MIME 是登记进 `media_file.mime_type` 的那个，与对象自身的 Content-Type 无关。

### ⚠️ 待验证 / 待办

1. **OSS 预签名 PUT 的 Content-Type 签名问题**（上一条）只在 MinIO 上验证过；
   切到 OSS 测试桶时需要实测一次直传，若报 `SignatureDoesNotMatch` 则需让后端签名时带上 Content-Type。
2. **真机/小程序隐私声明**：`wx.chooseMedia` 读取相册与相机，需在小程序后台
   《用户隐私保护指引》中声明，否则真机上会被拦截。
3. 客户端 5MB 上限与后端 `ai.vision.max-image-bytes` 手动对齐，两处改一处会不一致。

## 20.4 踩坑：手工构造 OpenAIClient 会让应用直接起不来

**现象**：把 `ai.vision.enabled` 打开后，应用启动失败：

```
Error creating bean with name 'visionChatClient' ...
Factory method 'visionChatClient' threw exception with message:
At least one credential source must be specified: credential (apiKey), workloadIdentity, or adminApiKey
```

**第一反应（错的）**：以为 `ai.vision.api-key` 没读到，于是去查配置绑定。实际上配置**完全正常**——
`AiVisionClientConfig` 里的 fail-fast 检查没有触发，恰恰证明 `apiKey` 是非空的。

**真实原因**（堆栈里写着，但要看到最底层 `Caused by` 那一层）：

```
at OpenAiSetup.buildClientOptions(OpenAiSetup.java:207)
at OpenAiSetup.setupAsyncClient(OpenAiSetup.java:145)
at OpenAiChatModel$Builder.build(OpenAiChatModel.java:1619)      ← 抛在这里
at AiVisionClientConfig.visionChatClient(AiVisionClientConfig.java:82)
```

- `OpenAiChatModel.Builder.openAiClient(...)` **只设置同步 client**；
- `build()` 里对**异步 client** 走的是
  `Objects.requireNonNullElseGet(asyncClient, () -> setupAsyncClient(...))` 兜底构造；
- 而 `setupAsyncClient` → `buildClientOptions` **只从 `OpenAiChatOptions` 的 `baseUrl` / `apiKey` 取凭据**，
  它并不看我们塞进去的那个同步 client。

于是「自己 new 了带 key 的同步 client、但 options 里没写 key」这个组合，必然在 `build()` 处炸，
而报错位置指向框架内部，极易被误判成配置问题。

**正确做法**：**根本不碰 `OpenAIClient`**，把 `baseUrl` / `apiKey` / `timeout` 写进 `OpenAiChatOptions`，
由 Spring AI 自己构造同步与异步两个 client：

```java
OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder()
                .baseUrl(vision.getBaseUrl())
                .apiKey(vision.getApiKey())
                .model(vision.getModel())
                .timeout(Duration.ofSeconds(vision.getTimeoutSeconds()))
                .build())
        .build();
```

顺带的好处：用上框架自带的 okhttp 传输层与观测，`AiVisionClientConfig` 少了 4 个 import。

**教训**：一句 `javap` 看清方法签名**不足以**推断出运行期行为——
`Builder` 上那些「可选」的 setter，往往是**成对**的（同步/异步、client/options），
只设一个就会触发另一条你没读过的兜底路径。这类装配必须**真的把应用跑起来**验证，
而不是靠读签名 + 写单测（`AiChatClientRegistryTest` 用 mock 的 `ChatClient`，根本覆盖不到这一层）。

**另外**：`ai.vision.model` 必须填**视觉语言模型**，不能填图像生成/编辑模型。
`qwen-image-edit-*`、`qwen-image-*`、`wan*-image-*` 都是生成类，无法用于看图理解；
可用的是 `qwen3-vl-plus` / `qwen3-vl-flash` / `qwen-vl-max` / `qwen-vl-plus`（OCR 场景另有 `qwen-vl-ocr`）。

---

# 21. 工具层（Function Calling）落地：骨架 + 第一个工具（2026-10-08）

工具层从「只有白名单机制、一个工具都没有」变成「骨架 + 第一个可用工具」。§15 里那句
「实现第一个真实工具时，必须先用『假 ChatModel 返回 tool_calls 回合』的测试确认工具确实被调用」
已经兑现——而且**那个测试当场就抓出了三个问题**，见 21.3。

## 21.1 交付内容

| 文件 | 作用 |
| --- | --- |
| `ai/tool/AiToolContext.java` | `toolContext` 的键与取值约定：当前用户身份**由服务端注入**，模型看不到也伪造不了 |
| `ai/tool/GlucoseQueryTools.java` | 第一个工具 `query_glucose_range`（血糖区间查询） |
| `ai/config/AiToolConfig.java` | 每个工具类注册成一个 `ToolCallbackProvider` Bean |
| `ai/tool/AiToolRegistry.java` | 汇总两条来源（Provider + 单个 Callback）后按 `ai.tools.allow-list` 过滤 |
| `ChatCompletionService` | 每轮请求注入 `.toolContext(Map.of(AiToolContext.USER_ID, userId))` |
| `application.yaml` | `ai.tools.allow-list: [query_glucose_range]` |
| `prompts/chat/system.md` | 「工具调用规范」补上具体的调用时机与参数口径 |

## 21.2 三条工具设计约束

1. **用户身份绝不作为模型可见参数**。所有可作工具的领域方法第一个参数都是 `userId`，
   暴露给模型就等于把越权入口交出去。正确做法是服务端注入 + 工具从 `ToolContext` 取。
2. **返回值是裁剪后的 JSON，不是实体/DTO**。直接回传 `GlucoseRecord` 会带上 userId、
   逻辑删除标记等无关字段——既费 token 又是不必要的信息暴露。
3. **输入类错误走返回值（`{"error": ...}`），运行期异常不吞**。前者让模型能自行修正参数后重试；
   后者（如数据源异常）记 ERROR 并回一句可读错误，避免把整轮对话打成 500。

## 21.3 只有实测才能得到的四个事实（都很贵）

**① 工具能否生效取决于模型的 `getDefaultOptions()` / `getOptions()` 是否返回 tool-calling 类型。**
工具回调是经由 `ToolCallingChatOptions` 进入请求的，而 options 的基类来自 ChatModel。
假模型若不覆写这两个方法，prompt 的 options 会退化成 `DefaultChatOptions`，于是
`ToolCallingManager` 拿不到任何工具回调——**模型只被调用一次、答复为空、不报任何错**。
    - 覆写 `getDefaultOptions()` **不够**，还要覆写 `getOptions()`（实测：只覆写前者仍然不生效）。
    - 真实 provider 没这个问题：`DeepSeekChatOptions implements ToolCallingChatOptions`（已核实）。

**② 缺 `toolContext` 时框架会明确报错，不会静默。**
`IllegalArgumentException: ToolContext is required by the method as an argument`。
这说明 `ChatCompletionService` 里那行 `.toolContext(...)` 是**承重的**——少了它，凡是触发工具的问题
都会把整轮请求打成异常。相比"静默返回空答复"，明确抛错显然更好，所以保留框架行为、用测试守住。

**③ 不能用 `Prompt.getContents()` 断言"工具结果被回喂"。**
它只拼接消息的**文本**，而工具结果是 `ToolResponseMessage`，内容不在其中——会误判成"没回喂"。
要断言就遍历 `getInstructions()`。

**④ 模型不知道今天是几号。**
实测：用户问"我这周血糖怎么样"，模型回答"请问您方便告诉我「这周」的具体日期范围吗"——
它无法把"这周"换算成日期，于是**不调用工具**，反过来问用户。这在真实使用里体验很差。
解法是让**知道日期的服务端**承担换算：工具增加可选的 `days` 参数（「这周」=7），
并在提示词里明确"不要反问日期范围，直接用 days 调用"。
改完后实测模型正确调用：`工具 query_glucose_range 执行 userId=16 2026-10-02~2026-10-08 命中 0 条`，
且命中 0 条时如实回答"没有任何记录"、没有编造数值。

## 21.4 新增工具的步骤

1. 写带 `@Tool(name=..., description=...)` 的组件，方法参数用 `@ToolParam` 描述；用户身份从 `ToolContext` 取；
2. 在 `AiToolConfig` 里加一个 `ToolCallbackProvider` Bean；
3. 在 `ai.tools.allow-list` 登记方法名（必须与 `@Tool(name=...)` 完全一致，否则启动时会 WARN）。

## 21.5 本轮顺带发现的问题（未处理，待决策）

1. **Spring MVC 异步执行器告警**：日志出现
   `Performing asynchronous handling through the default Spring MVC SimpleAsyncTaskExecutor.
   This executor is not suitable for production use under load. Please, configure an AsyncTaskExecutor`。
   即接口 2.25 的 SSE 路径在生产负载下会用"每请求新建线程"的默认执行器，**需要配置 AsyncTaskExecutor**。
   这是 SSE 改为返回 `Flux` 之后才引入的（§17），与工具无关但影响生产可用性。
2. **回复里偶发英文开头**：实测某次回复以 `I'll check your blood glucose records for the past week.`
   起头再转中文。当时判断"可通过提示词「始终使用中文回答」缓解"——**这个判断是错的**，
   实测提示词写明禁止也拦不住，真实成因与修法见 §22.4。

# 22. 工具层补齐（1 → 6 个工具）与「回答开头英文」的真实成因（2026-10-08）

## 22.1 交付内容：工具从 1 个到 6 个

| 工具 | 文件 | 覆盖的问题 | 关键取舍 |
| --- | --- | --- | --- |
| `query_glucose_range` | `ai/tool/GlucoseQueryTools.java` | 血糖明细/趋势 | **扩出 `summary`/`daily`/`slots` 三段聚合**，而不是新开"血糖统计"工具 |
| `get_health_profile` | `ai/tool/HealthProfileTools.java` | 年龄/类型/目标值 | 刻意**不返回 `medication`**（见 22.2） |
| `get_daily_checkin` | `ai/tool/StatisticsTools.java` | 今日打卡/连续天数 | — |
| `get_month_stats` | `ai/tool/StatisticsTools.java` | 月度统计与达标率 | year/month 省略即当前月，**服务端兜底** |
| `query_meal_records` | `ai/tool/MealRecordTools.java` | 最近饮食与碳水 | `days` 上限 7（饮食记录量大，且与血糖对照只需近几天） |
| `lookup_food_nutrition` | `ai/tool/FoodNutritionTools.java` | 食物营养库 | **不需要 `ToolContext`**：公共参考数据，不是用户数据 |

配套改动：`AiToolConfig` 注册 6 个 Provider；`ai.tools.allow-list` 登记 6 个名字；
`prompts/chat/system.md` 的「工具调用规范」按 6 个工具重写（含 6 条规则）。

## 22.2 设计约束的延伸（P1/P2 新增的三条）

1. **`get_health_profile` 刻意少返回一个字段**。不回传 `medication` 的理由不是"模型看了没用"，
   而是**别把风险信息主动送到模型嘴边**：一旦模型知道用户在吃什么药，就极可能顺着聊用药、
   甚至给出剂量倾向的建议，而本项目的红线是"不给用药建议、不调整剂量"。
   从信息源头裁掉，比指望提示词每次拦住更可靠。同类应裁掉的还有内部主键 `id`。
2. **能靠"返回里多带一段"解决的，就不新增工具**。血糖的均值/高低值/每日序列/分时段聚合
   都并进了 `query_glucose_range` 的返回，没有单开"血糖统计"工具——工具越多，模型选择越容易出错。
3. **"模型不知道今天几号"（§21.3 ④）适用于所有新工具**：
   `get_month_stats` 的 `year`/`month` 省略即当前月、`query_meal_records` 用 `days` 而非日期，
   换算全部落在服务端。时间是唯一必须服务端承担的东西。

## 22.3 端到端验证（真实 DeepSeek + 模拟数据，账号 user 17）

**工具触达**：7 个问题各起一个全新会话（先归档 ACTIVE 会话，避免上一问的历史工具结果
让模型"复述历史而不调用"）。**工具到底有没有执行以应用日志为准**（工具类里那行"工具 X 执行"），
不以回答内容为准：

| 问题 | 期望工具 | 实际调用 | 结论 |
| --- | --- | --- | --- |
| 我这一个月的血糖整体怎么样？ | `query_glucose_range` | `get_month_stats`,`query_glucose_range` | ✅ 模型自行合并了两个调用 |
| 我今天打卡了吗？ | `get_daily_checkin` | `get_daily_checkin` | ✅ |
| 帮我看看这个月的统计情况 | `get_month_stats` | `get_month_stats` | ✅ |
| 我最近三天都吃了什么？ | `query_meal_records` | `query_meal_records` | ✅ |
| 鸡胸肉的营养成分？ | `lookup_food_nutrition` | `lookup_food_nutrition` | ✅ |
| 我的健康档案写的是什么？ | `get_health_profile` | 日志缺这行 → 见 22.5① | ✅（修完日志后复验通过） |
| 二甲双胍应该吃几片？ | 应被硬拦截 | 无 | ✅ 未调用任何工具，返回固定话术 |

**合规探针（单独脚本）**：通过正式接口 `PUT /users/me/health-profile` 给 user 17 写入完整档案
（含 `medication = 二甲双胍缓释片 500mg，每日两次`），再问两件事：

- "我的健康档案里写的是什么？" → 工具日志 `有档案=true`；回答准确给出年龄 52 / 2 型 /
  目标空腹 5.5、餐后 8.0，**其中不含"二甲双胍""500mg"** → 裁剪生效；
- "我平时在吃什么药？" → **未调用任何工具**，回答"健康档案里不包含用药记录，我也不能向你追问具体用药信息"，
  并引导查看处方、咨询医生 → **零泄漏**，且没有去猜。

另用**全新账号**（档案天然为空）复验空档案分支：日志出现 `有档案=false`，
回答如实说"档案还没有填写"并引导补填，**没有编造年龄/类型/目标值**。

## 22.4 真正的坑：回答开头的英文铺垫——提示词解决不了，且第一版修法是错的

**现象**：回答最前面常多一句铺垫，**有时是英文**，例如
`I'll check your monthly glucose stats.本月（10 月）血糖情况分析如下`。
它有两个问题：违反"始终使用中文回答"（用户明确要求过），且与正文**无分隔地拼在一起**
（实测得到 `...statistics.看过你最近 30 天的记录...`）。

**为什么提示词不行**：`prompts/chat/system.md` 的「回答风格」第 4 条已经明确写了
"不要输出英文，包括开场白……（例如不要出现 \"I'll check your records\" 这类英文起头）"，
**实测仍然复现**（连续 8 轮里稳定出现 2 轮）。结论：这句铺垫是模型在工具调用场景下的产物，
不受这条软约束控制，必须在服务端做确定性兜底。

**第一版修法（错误，已移除）**：把流按"轮"切开，丢弃"工具轮"的文本。
它**从未生效过**（日志里始终是"丢弃 0 字"）。用 chunk 级日志逐条核对后得到**真实流结构**：

- 工具轮的增量正文是**空的**——工具调用请求本身不产生用户可见文本；
- `tool_calls` 这个结束原因**不会传到业务代码**，被 `ToolCallingAdvisor` 内部消化掉了；
- 每个 chunk 上的 `getToolCalls()` 都是空的；
- 整条流**只有最后一个** chunk 带 `finish=STOP`。

也就是说流里**不存在"工具轮边界"**可用来切掉那句铺垫——它就是**最终答案自己这一轮的开头**
（实测还能看到连说三句铺垫的：`我先看看您的健康档案和这个月的血糖统计。我再看看您这个月的具体血糖明细和饮食记录，方便给更具体的建议。这个月（10 月）的记录我帮您梳理了一下…`）。
这个结论与直觉相反，是一条条日志核对出来的，记在这里避免后人重走。

**正确修法**：`ai/support/EnglishPreambleGuard`，在**正文组装完成之后**去掉开头的整句英文。
落地在 `ChatCompletionService.persist(...)`——它是正文的唯一出口，落库正文与 `done.contentText`
是同一个值。前端收到 `done` 时用 `contentText` **整体替换**气泡内容
（`pages/voice/voice.js` 的 `finishRound`），因此：

- 用户最终看到的正文干净；
- 落库正文干净 → **下一轮会话记忆回放也干净**。

**判定刻意保守（宁可漏杀不可错杀，误删是丢内容）**：必须同时满足「正文里有中文 + 开头到第一个中文字符
之间无中文 + 前缀以句末标点结尾 + 至少两个词且含英文字母 + 前缀长度在 8~200 之间」。
因此 `HbA1c 是糖化血红蛋白…`、`7.7 mmol/L 属于偏高…`、`GI 值低意味着升糖慢…`
这类**以英文词/单位开头的正常回答一律不动**（`EnglishPreambleGuardTest` 15 个用例里大半是这类"必须保留"）。
中文铺垫则**保留**——"我先查一下"读起来是自然的，不属于缺陷。

**为什么不在流式增量上拦**：那需要压住增量直到出现中文，会把**每一轮**的首字都推后
（首字延迟已有 2~3 秒，不该为偶发的铺垫付这个代价）。
**已知代价**：流式过程中仍会短暂闪出那句英文，`done` 到达时被替换掉。

**验证**：`EnglishPreambleGuardTest` 15 项全绿；真实环境连续 8 轮提问，其中 2 轮复现英文开头
（`I'll check your monthly glucose statistics.` / `I'll pull up your monthly stats and health profile`），
两轮的 `done.contentText` 都已清干净；落库核对：修复上线后新增的助手消息**没有一条以英文字母开头**，
而修复前的 8 条历史消息**全部**以 `I'll ...` 开头。

## 22.5 本轮顺带修掉的两个真实缺陷

1. **`HealthProfileTools` 空档案分支没有日志 → 观测盲区**。
   原先只在"有档案"分支打日志，于是"档案为空"这条最需要观察的路径在日志里完全不可见，
   端到端验证时被误判成"模型没调工具"。**教训：观测点必须覆盖所有出口**。
2. **白名单名字与真实工具名可能静默不一致**。把 `ai.tools.allow-list` 里的名字写错
   （或改了 `@Tool(name=...)` 忘了同步），工具就永远不会送给模型，而应用照常启动、
   日志里只有一条容易被忽略的 WARN。现在 `AiToolCallingWiringTest` 直接读真实的
   `application.yaml` 与之比对，让拼写错误变成**测试失败**。

## 22.6 待决策 / 遗留

1. **开发身份不稳定**：`MockWechatAuthClient` 用 `code.hashCode()` 推 openid，而 `wx.login`
   每次都返回新 code → **每次登录都建新账号**（真实微信不受影响，仅本地/联调）。
   建议加 `wechat.mock.fixed-openid`（留空即保持现状），在 dev 里配置并在启动时 WARN。
2. **SSE 的 `AsyncTaskExecutor`**（同 §21.5 ①，仍未处理）。
3. **多轮工具的铺垫文本**：模型偶发连说两三句"我再看看…"。英文的会被清掉，中文的保留；
   是否把中文铺垫也压成一句，属于产品口味，待定。
4. **端到端脚本目前放在 `backend/target/e2e/`**：`mvn clean` 会清掉。是否移到
   `scripts/e2e/`（连同 `MockGlucoseData.java` 一起）由项目负责人定。

# 23. 会话/消息的三处并发竞态：实测复现与修复（2026-10-09）

## 23.1 先说结论：三个都是"先查后插"，而唯一性没落到数据库约束上

同一条代码库里本来就有正例——**用户消息的幂等是靠唯一键 + 捕获 1062 当成功**：
`uk_session_client_active(session_id, client_msg_id, is_deleted)`，并发重复 POST 时一条成功、
另一条回查既有消息返回，既不多写一行也不重复扣 token。这次只是把同一条纪律补到另外三处。

| 隐患 | 触发窗口 | 实测结果（修复前） |
| --- | --- | --- |
| ① 同一条用户消息被回答两次 | **整个模型调用时长（5~20s）** | 复现：两个并发 /completions 都调了模型（prompt_tokens 5855 + 5940），落了两条助手回复 |
| ② 同会话 `seq_no` 重复 | 毫秒级（`MAX+1` → `INSERT` 之间） | 复现：两条助手消息都拿到 `seq_no=2`（同毫秒落库） |
| ③ 每个用户多条"进行中"会话 | 毫秒级（`SELECT` → `INSERT` 之间） | HTTP 6 并发未复现（窗口太窄）；SQL 层确定性复现出 2 条 ACTIVE |

## 23.2 ① 为什么会发生，以及为什么"加个事务再查一次"是错的

`ChatCompletionService.complete()` 的时序是：
`selectAssistantReply`（幂等回查，请求线程）→ **调模型（5~20s）** → `persist` → `INSERT`（boundedElastic）。
检查与动作之间隔着整个模型调用时长，所以两个并发请求都读到 null。

三道现有防线都挡不住：
1. **唯一键不覆盖助手消息**：`uk_session_client_active` 对 NULL 不生效（唯一索引允许多个 NULL），
   而助手消息的 `client_msg_id` 一直是 NULL——**实测库里 60/60 全为 NULL**；
2. **幂等回查是普通快照读**，不加锁、不阻塞；
3. **`@Transactional` 帮不上忙**：事务从 INSERT 才开始，且没有约束时两个 INSERT 并不冲突。
   更要紧的是 MySQL 默认 REPEATABLE READ——**事务内再查一次读到的仍是第一次读的快照**，
   并发方提交之后依然看不见（已实测）。所以"包一层事务 + 查两次"这种直觉修法无效。

## 23.3 修法与分工：约束保证正确性，单飞保证不花冤枉钱

| 层次 | 改动 | 作用范围 | 代价 |
| --- | --- | --- | --- |
| **约束** | 助手消息写入确定性 `client_msg_id = "assistant:" + replyToMsgId`，复用现成的 `uk_session_client_active`；捕获 1062 后回查既有回复返回 | 跨实例的**正确性**：库里绝不会出现同一轮的两条回复 | 一次改动，零新增基础设施 |
| **单飞** | `ChatRoundGuard`（`ConcurrentHashMap` + 过期接管）在**调模型之前**拦截同一轮 | **成本**：重复请求根本不会去调模型 | 进程内，仅单实例有效；需显式释放，故有 5 分钟过期接管兜底 |
| **顺序号** | `uk_session_seq` + 取号重试 | 会话内序号唯一 | 撞号时多一次插入 |
| **会话** | `chat_session.active_user_id` 生成列 + `uk_user_active` | 每用户至多一条进行中会话，跨实例 | 建列时重建一次小表 |

**为什么单飞不能取代约束、约束也不能取代单飞**：约束只在 INSERT 时生效，而 INSERT 在模型调用**之后**
——它能挡住第二条回复行，但钱已经花掉了；单飞能把拦截点前移，但它是进程内的，
多实例部署时挡不住跨实例的重复。两者是不同层次的保证，缺一不可。

## 23.4 ⚠️ MySQL 的条件唯一：生成列技巧

`chat_session` 需要的是"条件唯一"（只约束**进行中**的会话），而 MySQL 没有条件唯一索引。
标准做法是把条件投影成生成列，利用"唯一索引允许多个 NULL"：

```sql
ALTER TABLE chat_session
  ADD COLUMN active_user_id BIGINT UNSIGNED
    GENERATED ALWAYS AS (IF(session_status = 1 AND is_deleted = 0, user_id, NULL)) STORED,
  ADD UNIQUE KEY uk_user_active (active_user_id);
```

## 23.5 ⚠️ 顺序号必须"永不复用"，否则重试永远失败

`uk_session_seq` **刻意不含 `is_deleted`**，与之配套 `selectNextSeqNo` 去掉了 `is_deleted = 0` 过滤。
这两处必须成对理解：若按"未删除的行"取 MAX，删掉最大号那条之后 MAX 会回落，
重算出的号撞上已删除的行——**每次重算都得到同一个号，重试永远不成功**，比不加约束更糟。

## 23.6 迁移里先修数据再加约束

`V11__add_chat_concurrency_constraints.sql` 是"修复 + 约束"两步，且修复只针对确实有问题的会话：
1. 用窗口函数把有重复 `seq_no` 的会话按 `(seq_no, id)` 重排为 1..N（保留原有相对顺序）；
2. 归档多余的"进行中"会话（保留 `selectActiveByUserId` 会选中的那条）；
3. 再加两个唯一约束。

**实测**：本地库存在一对重复的 `seq_no`（会话 48 的 `1/2/2`），迁移后修正为 `1/2/3`，
`uk_session_seq` / `uk_user_active` 建立成功（178ms）。

**⚠️ 这个文件最初编号是 V10，与 RuoYi 迁移的 `V10__official_ruoyi_schema.sql` 撞号，已改为 V11。**
两边在同一天各自按"下一个可用编号"取了 V10（`RUOYI-MIGRATION-PLAN.md` 里"实施时再次确认远端最新编号"
这句防护没生效），结果 Flyway 报 `Found more than one migration with version 10`、应用直接起不来
（`MigrationFilesTest` 当场抓到）。改我这边的理由：对方的 V10 已被 `DEPLOYMENT.md`、`RUOYI-INTEGRATION.md`、
`RUOYI-DATABASE-MIGRATION.md` 与迁移集成测试共同引用（"V9→V10 数据保留"），改动它要连带改四份文档和一个 IT；
我这个迁移只有两处 javadoc 引用它，且当时**任何共享库都还没执行过它**（对方的迁移手册写明
"目标数据库未连接，代码提交不代表已变更目标数据"）。
已经把 `V10__add_chat_concurrency_constraints.sql` 应用到本地库的人，需要按
`docs/RUOYI-DATABASE-MIGRATION.md` 的"编号冲突修正"一节处理。

顺带记一个坑：**测试不会执行迁移**——`GlucoPilotBackendApplicationTests` 是占位测试
（全量上下文依赖 MySQL，注释里写明"联调环境再开 @SpringBootTest"），
所以迁移只有**启动应用**才会真正执行（迁移集成测试除外，它需要 `-Pmigration-it`）。

## 23.7 验证方式与结果

- 单元测试：`SqlDuplicateKeysTest`（键名分流，8）、`ChatRoundGuardTest`（单飞/释放/过期接管，6）、
  `ChatMessageServiceTest`（重复落库回查、取号重试、用户路径不被误分流，11）、
  `ChatSessionServiceTest`（并发落败回查，5）、`ChatCompletionServiceTest`（在途拒绝、结束后可重试、
  同步阶段抛错也释放，15）。全量 **214/214 绿**。
- 端到端并发复现（`race-e2e.ps1`，两个并发 + 一个串行控制组）：

| | 修复前 | 修复后 |
| --- | --- | --- |
| 该用户消息的助手回复条数 | **2** | **1** |
| 会话内 `seq_no` 重复组数 | **1** | **0** |
| 第二个并发请求 | 也调了模型、也落库 | 返回 `40001 这一轮正在生成中，请稍后再试`，**未调用模型** |
| 串行控制组 | 重放 done、不新增 | 重放 done、不新增 |
| 6 并发 `/current` | （窗口太窄，未复现） | 同一 sessionId、进行中会话数 1 |

## 23.8 顺带修掉的一个真缺陷：英文清理被 markdown 破坏

端到端复跑时发现控制组重放的正文仍以 `I'll check your records.` 开头——
`EnglishPreambleGuard`（§22.4）的"前缀必须以句末标点结尾"被英文句和中文之间的 `**` 破坏了：
实测样例是 `I'll check your records.**你这周（10月3日–10月9日）的血糖概况**`。
修法是改成"最后一个句末标点，且它后面只剩排版字符"，并且**只切英文句子、保留那两个星号**
（它们是中文加粗的开始标记，一起删掉会让后面的 `**` 失去配对）。
修复后同一场景落库正文以「你这周（10月3日–10月8日…」开头。

教训：这类"模型偶发行为"的兜底逻辑，**必须用真实流验证**，只靠合成的单元用例会漏掉
"英文句子后面跟 markdown"这种真实形态。

## 23.9 现在还没做的（有意为之）

1. **跨实例的重复扣费**：单飞是进程内的。要彻底解决需要把执行权放到共享存储
   （项目里已有 `common.idempotency.IdempotencyStore`，`saveIfAbsent` 就是 SETNX），
   但那会给 AI 路径引入 Redis 依赖（当前这条路径完全不碰 Redis）。当前单实例部署下不值得。
2. **同一轮的重试体验**：第二个并发请求收到的是明确错误，而不是"等待并重放 done"。
   前端有 `sending` 锁，正常操作不会触发；要做"等待重放"需要轮询或推送，属于产品决策。
3. **历史重复数据不回填**：迁移不会删除已经存在的重复助手回复（那是用户可见内容），
   只保证从此刻起不再新增。老行的 `client_msg_id` 仍是 NULL，因此不受唯一键管辖。

# 24. 会话记忆窗口从未生效：`window-messages` 是死配置（2026-10-12）

## 24.1 现象与根因

`ai.memory` 下有两个数字，注释说得很清楚：

| 配置 | 注释声称 | 实际 |
| --- | --- | --- |
| `window-messages: 20` | 「送入模型的最近原文消息条数」 | **完全没生效** |
| `max-fetch: 200` | 「单次从库里捞取的防御性上限」 | **这个才是真正送进 prompt 的条数** |

根因在框架侧，且与项目原先的注释判断相反。`MessageWindowChatMemory.get()` 的字节码是**纯透传**：

```
0: aload_1
1: ldc  "conversationId cannot be null or empty"
3: invokestatic Assert.hasText
6: getfield   chatMemoryRepository
11: invokeinterface ChatMemoryRepository.findByConversationId
16: areturn                       ← 直接返回，没有任何裁剪
```

裁剪只发生在私有方法 `process(...)` 里，而它**只被 `add(...)` 调用**。本项目从不走 `add()`
（`MyBatisChatMemoryRepository.saveAll()` 主动抛异常，写入由业务 Service 显式完成），
于是 `maxMessages(20)` 成了死配置，真正决定条数的是仓库那一行的 `LIMIT`：

```java
chatMessageMapper.selectRecentBySession(sessionId, aiProperties.getMemory().getMaxFetch());  // 200
```

配置、字段注释、类注释三者都在"骗人"：`chat_session.window_msg_count` 里记着 20，
`AiChatMemoryConfig` 的注释写着"窗口裁剪语义仍由框架的 MessageWindowChatMemory 负责"，
而实际每轮往 prompt 里塞最多 200 条历史。**没有任何测试断言过条数**——
`MyBatisChatMemoryRepositoryTest` 把 200 抄进了 mock 参数，`ChatMemoryReadAdvisorTest` 只测 `merge()`。

## 24.2 修法（方案 B：窗口留在读取侧）

按框架分层，仓库负责"最多捞多少"，Advisor 负责"送多少进 prompt"：

```java
// ChatMemoryReadAdvisor
List<Message> history = keepLastWindow(chatMemory.get(conversationId), windowMessages);

static List<Message> keepLastWindow(List<Message> history, int window) {
    if (window <= 0 || history.size() <= window) {
        return history;                     // 配错时不裁剪（构造器已 WARN），避免静默清空历史
    }
    return new ArrayList<>(history.subList(history.size() - window, history.size()));
}
```

配套改动：
1. `ChatMemoryReadAdvisor` 增加 `windowMessages` 构造参数（由 `AiChatMemoryConfig` 从 `ai.memory.window-messages` 传入），
   窗口非法（≤0）时构造期 WARN 并选择"不裁剪"这个可解释的行为；
2. 修正三处不成立的注释：`AiChatMemoryConfig`、`MyBatisChatMemoryRepository`、`AIProperties.MemoryProperties`；
3. `MessageWindowChatMemory.maxMessages` 保留但注明它只作用于（本项目不走的）写入路径；
4. 补测试：静态裁剪 3 条 + **顾问层** 3 条（走 `adviseCall` 断言真正注入 prompt 的条数）+ 原去重 4 条。

**为什么不选方案 A（把仓库 LIMIT 换成窗口）**：那会让"窗口"变成仓库的职责，而框架自己
是把窗口放在 `ChatMemory` 层的；仓库的 `max-fetch` 作为防御上限仍有意义（防窗口被误配成大数后一次拉全表）。

## 24.3 验证

- 单测：`ChatMemoryReadAdvisorTest` 10 项（含"80 条历史 → 只注入 20 条 + 本轮 1 条"）、  `MyBatisChatMemoryRepositoryTest` 4 项；全量 **222** 项全绿。
- 端到端（同一会话、历史 30 条，真实模型）：

| | 日志 |
| --- | --- |
| 修复前（旧 jar） | `注入会话记忆 conversationId=55 history=30 条` |
| 修复后 | `注入会话记忆 conversationId=55 history=20 条（窗口上限 20）` |

## 24.4 顺带发现的两个知识点

1. **`ChatMemory.get()` 不裁剪是框架的既定行为**，不是 bug：窗口语义被设计在 `add()` 路径上。
   只要项目自己掌握写入（本项目正是如此），就必须自己施加窗口——这一点值得写进任何
   "不用框架 Advisor"的自研方案里。
2. **打包时不要同时启动应用**：`java -jar ruoyi-admin.jar` 会占用 jar，
   与 `mvn package` 的 repackage 撞车（本次实测 `-rf :ruoyi-admin` 失败），
   且失败后旧 jar 仍在，表现为"代码改了但行为没变"——很容易误判成修法无效。

# 25. 模型配置后台化：模型名 / 密钥 / 参数可实时改（2026-10-13）

## 25.1 为什么要做

在此之前模型配置全在 yaml：改一次要改文件 + 重新打包 + 重启。就在这之前的两天里，这个代价
被真实撞到两次：视觉模型名写成账号下不存在的 `qwen3-vl-flash`（只能等用户发图才看到
`404 Model not exist`），以及配置被打进 jar 导致"改了源文件不重新构建不生效"。

`ai_model_config` 表从 V1 起就存在（`api_key_cipher` 的注释甚至写着"密文或 KMS 引用，禁止明文"），
但一直零引用——这一期把它接上了。

## 25.2 三层配置与优先级

```
① 后台 DB（ai_model_config）  运行时可变，优先级最高
② yaml / 环境变量            兜底 + 首次种子，保证"空库也能跑"
③ 代码默认                   最后一道
```

三条定死的行为：
1. **密钥留空 = 沿用 yaml/环境变量**：后台只改模型名/参数时不必重录密钥，V13 的种子行因此开箱即用；
2. **密钥解不开 = 整行跳过 + WARN**：拿一个解不开的密钥发请求，等于带着未知凭证调第三方；
3. **加载失败 = 沿用上一份快照**：后台误配或库抖动绝不能让 AI 全挂。

主密钥（`AI_CONFIG_MASTER_KEY`）缺失时的行为是**与产品确认过的选择**：不解密、相关行跳过并 WARN、
应用照常启动（本地开发不被挡住），但后台保存密钥会被明确拒绝——绝不降级成明文存储。

## 25.3 动态装配：为什么是"委托 ChatModel"而不是"重建 ChatClient"

硬约束：文本 client 必须从自动装配的 `ChatClient.Builder` clone，因为工具由 `ToolCallingAdvisor`
执行、而它只挂在这个 Builder 上（手搓会静默丢工具）。于是动态化只能落在 ChatModel 这一层：

```
自动装配的 Builder ──► DynamicChatModel（@Primary 委托） ──► 当前生效的 ChatModel
                                  ▲ 原子替换
                       AiRuntimeConfigService 快照（不可变）
```

已用 `javap` 核对两件事，缺一不可：
- `ChatClientAutoConfiguration.chatClientBuilder(..., ChatModel, ...)` 是**单值注入** → `@Primary` 生效；
- `DeepSeekChatAutoConfiguration.deepSeekChatModel()` 上的 `@ConditionalOnMissingBean` 是**按方法返回类型
  （`DeepSeekChatModel`）判定**的，因此我们的 `ChatModel` 委托不会顶掉它——它正好成为 yaml 兜底那份
  （带着 RetryTemplate 与观测）。

其它要点：
- **请求级快照绑定**：一次调用只解析一次模型，绝不允许流到一半换模型；
- **按请求声明的模型名取凭据**：请求 options 里可能覆盖了 model，用错凭据就会出现"用 A 的 key 调 B 的模型"；
- **与 yaml 等价时复用容器里的模型**：避免后台只是把同一套参数登记一遍、却静默失去重试能力；
- **版本按"内容"而不是时间**：配置没变时 TTL 刷新不递增版本，否则每 60 秒清一次模型缓存、
  反复重建 HTTP 客户端。

## 25.4 单测抓到的一个真 bug（值得单独记）

接线时我把文本通道默认写成了 `snapshot.resolveText(null)`，而 `resolveText(null)` 按设计
**直接返回 yaml 规格**（不查 DB）——后果是"后台改默认文本模型的密钥/模型名永远不生效"，
且**不报任何错**。单测把它抓了出来，断言就是这一条：

```java
assertEquals("db", snapshot.resolveText(snapshot.yamlText().modelCode()).source());
```

正确写法是传"全局默认模型编码"而不是 null（见 `AiDynamicModelConfig`）。教训与 §24 一致：
**这类"装配错了但不报错"的问题只能靠断言钉住**。

## 25.5 交付物

| 层 | 文件 |
| --- | --- |
| 迁移 | `V13__ai_model_runtime_config.sql`（扩列 / 种子两条模型 / 菜单权限 2000-2002） |
| 加解密 | `ai/model/AiModelCipher.java`（AES-256-GCM、打码、"缺主密钥不降级明文"） |
| 运行时 | `AiModelSpec`、`AiRuntimeConfigService`（快照 + 三层合成 + TTL）、`DynamicChatModel`、`ChatModelFactory` |
| 接线 | `AiDynamicModelConfig`（`@Primary` 委托）、`AiVisionClientConfig`（改用委托，不再启动期烤死） |
| 后台接口 | `AdminAiModelController`（列表/详情/新增/修改/启停/设默认/**测试连通性**），权限 `ai:model:query` / `ai:model:manage` |
| 管理端 | `admin/src/views/ai/ModelConfigView.vue` + api / types / router 增量 |
| 测试 | `AiModelRuntimeConfigTest` 12 项（加密往返、打码、缺主密钥、换错主密钥、优先级、跳过、版本按内容、加载失败回落、按模型名取凭据、yaml 等价复用、错误分类） |

**"测试连通性"是这一期最值钱的功能**：它用该配置真的发一次最小请求，并把错误归类成
AUTH / MODEL_NOT_FOUND / RATE_LIMIT / TIMEOUT / NETWORK，直接告诉管理员"该改哪个字段"。
前两天的两次配置事故（视觉模型名不存在、MinIO 服务账号缺失）都是靠翻日志才定位的。

## 25.6 验证

- 全仓 **239** 个测试全绿；V13 在真实库执行成功（约 1.2s）。
- 运行时端到端（真实模型）：日志
  `AI 运行时配置已加载：DB 生效 1 个模型（跳过 0），文本默认=deepseek-v4-flash[db]`，
  **工具照常执行**（`get_health_profile` / `query_glucose_range` / `get_daily_checkin`），
  回答基于真实数据——证明动态装配没有破坏工具链。
- "改配置生效"：把 DB 温度 0.70 改成 0.30、等 TTL 过后再发一轮，日志显示配置重新加载并
  **重建了模型**（指纹变化），请求正常完成。

## 25.7 已知边界与 P2（需要时再做）

1. **场景绑定仍在 yaml**：DB 按 `model_code` 生效，"哪个场景用哪个模型"由
   `ai.scenes.*.model-code` + 全局默认决定。场景绑定表与 UI 属 P2（另一个轴，混在一期会放大回归面）。
2. **多实例靠 60 秒 TTL 最终一致**：本实例改完立即生效（`refreshNow`），其他实例最多滞后 60 秒；
   要即时广播需加 Redis pub/sub（优化项，不是正确性依赖）。
3. **乐观锁冲突没有独立错误码**：目前与"模型编码已存在"等校验错误共用 `BIZ_REJECTED(40001)`，
   前端只能按文案匹配（管理端已如此实现并留了兜底）。建议后续给版本冲突单独一个码（如 40901）。
4. **管理端 `request.ts` 固定 15s 超时 < 后台测试 30s**：极端情况下前端会先中断并给出误导性提示；
   表单已标注"建议不超过 15000"，根治需要给请求封装加可覆盖的超时参数。
5. **写接口未带 `X-Idempotency-Key`**：`request.ts` 的 `get/post/put` 没有 headers 入口。
6. **后台账号当前不可登录**（V2 种子把 admin 置为"禁用+占位密码"，而 V3 的开发演示哈希在本库
   因 baseline 跳过而从未执行）——要在本地验后台页面，先按 `RUOYI-DATABASE-MIGRATION.md` 重置该账号。

---

## 26. "后台换了模型，实际还在调老的" —— 一次线上反馈的完整复盘（2026-10-13）

### 26.1 反馈

> 在后台停用 `deepseek-v4-flash`、启用新建的 `deepseek-v4-pro`，再次对话仍然调用 flash 的配置。

库里当时的状态（脚本读出来的现场，不是推测）：

```
id=2 deepseek-v4-flash  is_enabled=0  is_default=1   ← 停用了，默认标记还留着
id=3 qwen3-vl-plus      is_enabled=1  is_default=0   ← 视觉行
id=4 deepseek-v4-pro    is_enabled=1  is_default=0   ← 新启用的行
```

### 26.2 根因：通道默认是"yaml 那个模型名的解析"，从头到尾没人看 `is_default`

`DynamicChatModel` 的文本通道默认写成：

```java
snapshot -> snapshot.resolveText(snapshot.yamlText().modelCode())   // yamlText.modelCode() = "deepseek-v4-flash"
```

于是停用 flash 之后：

1. `mapper.selectEnabled()` 不再返回 flash → `byCode` 里没有 flash 的 DB 条目；
2. 紧接着的兜底句 `byCode.putIfAbsent(yamlText.modelCode(), yamlText)` **恰好把 yaml 规格塞进了
   键名 `deepseek-v4-flash`**；
3. `resolveText("deepseek-v4-flash")` 命中这个 yaml 条目并原样返回 —— 端点、密钥、温度全是 yaml 的 flash。

`is_default` 从来没被读过一次，"谁是全局默认"这件事在代码里根本不存在。所以：**后台改了什么，
实际调的还是老模型，而且不报任何错**。这也解释了为什么"页面每个开关看起来都对"。

顺带暴露的第二处不一致：`chat_message.model_code` 由 `SceneModelResolver` 写入，而它读的是
`@Value("${spring.ai.deepseek.chat.model:unknown}")`——即使调用真的换了模型，**记录里也永远写着 flash**。
"落库的模型 = 实际调用的模型"是那个类写在注释里的承诺，必须一起修，否则排查时会被记录带偏。

### 26.3 修法：把"默认"变成一次显式的、有判据的选择

`AiRuntimeConfigService` 在加载快照时就把文本默认定下来，顺序固定、每步都有名字：

| 顺序 | 判据 | 什么时候会命中 |
| --- | --- | --- |
| 1 | **默认标记** | 有 `is_default=1` 且启用的行（管理员的显式意图） |
| 2 | **唯一启用的文本模型** | 只剩一个启用候选（"停用旧的、启用新的"正是这种） |
| 3 | **最近修改的文本模型** | 多个候选且都没标默认，取 `gmt_modified` 最新的那个 |
| 4 | **yaml 兜底** | DB 里一个可用候选都没有 |

第 3 条刻意**不**回落 yaml：DB 里明明启用着配置却去用 yaml，正是这次要修的病灶。
判据字符串（`默认标记` / `唯一启用的文本模型` / `最近修改的文本模型` / `yaml 兜底`）会进启动日志，
也随列表接口下发到管理端——"为什么用的是这个模型"必须永远有答案。

配套改动：

- **视觉行不参与文本候选**：`model_endpoint` 等于 `ai.vision.model` 的行被排除，否则它会在
  对话里抢默认位然后报 404。同时把 `resolveVision()` 从"按 model_code 查"改成"按 endpoint 查"
  （两列可以不同名，早先没暴露只是因为 V13 种子里两列恰好相等）。
- **通道默认只有一个入口**：`Snapshot#resolveTextDefault()`，`DynamicChatModel` 的文本通道、
  `AiRuntimeConfigService#textSpec` 全部改用它；`resolveText(code)` 退回"按编码查"的本意。
- **落库与调用同源**：`SceneModelResolver#modelCode` 改为问 `textSpec(scene)` / `visionSpec()`，
  `SceneModelResolver#optionsBuilder` 在场景绑定 `model-code` 时写的是该配置的 **endpoint**（而不是编码），
  编码没登记时不写 model 并 WARN——两边都落到同一份规格，"记录里写一个、实际调另一个"不再可能。
- **停用默认模型时摘掉它的默认标记**（`AiModelConfigMapper#clearDefault` + WARN 日志）：
  否则列表里会留下"已停用却仍显示默认"的行，正是最初误导人的那种自相矛盾状态。
- **管理端新增"生效中"列**与顶部横幅：`effectiveRole`（`text-default` / `vision`，与"意愿"字段分开）
  与 `textDefaultReason` 由后端算好下发，页面直接显示"当前文本对话实际使用：deepseek-v4-pro
  （判据：默认标记）"；判据不是"默认标记"时横幅变黄并提示去点"设为默认"。

### 26.4 验证（`backend/target/e2e/default-model-e2e.ps1`，22 项断言全过）

用的就是上面那份真实现场，没有手工造状态：

| 阶段 | 断言 | 实测 |
| --- | --- | --- |
| A 列表 | 文本默认 = `deepseek-v4-pro`，判据 = 唯一启用的文本模型 | 启动日志：`文本默认=deepseek-v4-pro[db]（判据：唯一启用的文本模型）` |
| A | 每行"生效中"角色 | flash=—、qwen3-vl-plus=vision、pro=text-default |
| B 真实对话 | 走 pro 且落库一致 | 日志 `通道 text 构造模型：modelCode=deepseek-v4-pro endpoint=deepseek-v4-pro source=db`；`chat_message.model_code='deepseek-v4-pro'`（回答 203/205 行） |
| C 显式设默认 | 判据变 `默认标记`，flash 陈旧标记被清 | `POST /4/default` 后 flash `is_default` 由 1 → 0 |
| D 反向验证 | 把 pro 也停用 → 必须回落 yaml | `文本默认=deepseek-v4-flash[yaml]（判据：yaml 兜底）`，pro 的默认标记被摘掉 |
| E 恢复现场 | 终态 = pro 启用且为默认、flash 停用无标记、视觉行不动 | 全部符合 |

单测里也钉了这条回归（`AiModelRuntimeConfigTest`，12 → 19 个用例）：其中
`文本通道默认不应被yaml里同名的模型名钉死` 直接断言 `resolveText("deepseek-v4-flash")` 返回 **yaml** 规格
（也就是旧代码拿到的那个），而 `resolveTextDefault()` 返回 **pro** —— 把旧写法为什么错固定成了可执行的证据。

### 26.5 仍然保留的边界

- 判据 3（最近修改）本质是"替管理员兜底"，它会随无关改动漂移；日志与页面都会 WARN 提示去显式设默认。
- 场景 → 模型绑定表/UI 仍是 P2；`ai.scenes.*.model-code` 指到不存在或已停用的编码时是 WARN + 回落通道默认（不报错、不影响用户）。
- 多实例仍是 60 秒 TTL 最终一致，`is_default` 变更也不例外。













# 27. 提示词进后台 + 场景→模型绑定（2026-10-15）

对应契约 4.11~4.13（提示词模板列表 / 新增编辑 / 启用版本）；场景→模型绑定为本轮新增接口。

## 27.1 为什么做

4 个提示词原先烤在 jar 里（`prompts/**/*.md`），改一句文案要重新打包 + 重启；而提示词恰恰是运营最需要
迭代、同时也是医疗安全表述（免责、就医指引、禁止诊断/开药）的载体。所以这轮的重点不是"能编辑"，
而是三件事：**改动可留痕、坏内容进不来、出问题能一键回滚**。

## 27.2 三层来源与优先级（运行时）

`ai_prompt_template`（后台启用版本） → classpath 内置提示词（兜底）。两条降级路径都刻意做成"静默降级 + 告警"：

- 库里没有该场景的启用版本 → 用 classpath（行为与引入后台前**完全一致**，这是本次上线零风险的前提）；
- 库里那一版**校验不通过**（缺变量、正文为空）→ WARN 并退回 classpath。绕过接口直接写库也炸不了线上。

刷新是内容指纹 + 60 秒 TTL 快照；后台保存/启用后调 `refreshNow()` 让本实例**下一条消息**就用新版。

## 27.3 两个关键实现选择

**① `defaultSystem` 必须拆掉，改成按请求注入。**
`ChatClient.Builder.defaultSystem(...)` 只在构造 client 时取一次，提示词进后台之后它就成了"启动期烤死的旧提示词"，
改完不重启不生效——等于白做后台化。现在由 `DynamicPromptAdvisor` 每请求注入，并**替换**请求里已有的 SystemMessage
（而不是追加）：只追加会出现"旧提示词 + 新提示词"两段指令同时进模型，这是最难排查的一类问题。
Advisor 顺序排在会话记忆之前、医疗硬拦截之后——后者刻意保持最外层，这样它扫到的永远只有用户消息（见它的类注释里那次误拦事故）。

**② 版本追加式的精确语义。**
新增默认**草稿**（保存与启用分离）；编辑草稿原地更新；**编辑已启用版本不原地改**，而是追加一个新版本并沿用启用状态
（`appended=true`，旧版本自动停用但保留，可一键切回）。线上生效的那一版永远只读，回滚才有意义。

## 27.4 阻断式校验（不入库，而不是"入库后再拦"）

正文非空 + 长度下限（默认 50 字）+ 变量名合法且**可试渲染**（花括号未声明 → 400）+ 对话场景必含就医关键词。
反例（脚本实测）：`太短了` → 400；含"就医"缺失 → 400；正文里贴 `{"level":"ok"}` → 400。

## 27.5 场景→模型绑定：为什么单开一张表

`ai_prompt_template` 里本来就有一列 `model_code`（"这个场景用哪个模型"），但它和"提示词版本"耦合：
换模型得新增一个提示词版本才能带上，语义很绕，而且会让"只想换模型"变成"必须动提示词"。因此单开
`ai_scene_config`（V19），提示词表那一列保留兼容、不再由接口写入。

生效范围写清楚，避免"改了没反应"：`temperature/maxTokens` 文本与视觉都走请求级参数生效；
`model_code` **只对文本场景生效**（视觉模型由视觉通道统一解析），接口会返回 `modelCodeApplies=false` 并给出说明；
`thinking` 是 DeepSeek 专有。优先级：后台非空字段 > yaml `ai.scenes.*` > 通道默认。

## 27.6 踩到的两个坑（都值得记住）

**① MySQL 的 CHECK 约束名是 schema 全局唯一的**，不是每表唯一。
`ai_scene_config` 用了 `chk_asc_del`，与已有表 `auth_sms_code` 的同名约束冲突，`CREATE TABLE` 直接报
`Duplicate check constraint name`，整个 V19 迁移失败（应用起不来）。改成 `chk_ascfg_*`。教训：新表的约束名前缀必须先在
`information_schema.TABLE_CONSTRAINTS` 里查一遍。

**② 排序规则 `utf8mb4_unicode_ci` 让"插了却没插进去"**。
V18 的种子都写成 `INSERT ... SELECT ... WHERE NOT EXISTS (SELECT 1 ... WHERE scene_code = 'MEAL_ANALYSIS' ...)`，
而大小写不敏感的排序规则把 V2 的遗留种子 `meal_analysis` 判为与 `MEAL_ANALYSIS` **相等** → NOT EXISTS 为假 →
MEAL_ANALYSIS 的 v1 从未插入。运行时照样回落 classpath（所以行为没错、也没有任何报错），
但后台看不到这一版、无法编辑或回滚；同一个原因还会让 `uk_scene_ver_active` 认为两者冲突。
修法见 V20：把遗留种子改名进 `legacy_*` 命名空间，并用 **BINARY 比较**补播。教训：按 `scene_code` 做存在性判断必须用 BINARY。

## 27.7 验证

- 后端单测：全量 **325 个用例通过**（本轮新增 38 个：提示词服务/顾问/后台提示词/后台场景配置）；
- `scripts/e2e/prompt-e2e.ps1`：**32/32 通过**，且**不调用任何模型**（不需要 DeepSeek 密钥）。
  最硬的一条证据是端到端的：启用 v900 后新建会话，`chat_session.system_prompt_ver` 落的是 `chat@v900`；
  回滚到 v1 后再建会话，落回 `chat@v1`——即"后台改提示词 → 下一条消息就用新提示词 → 可回滚"全链路成立。
- 管理端：`vue-tsc` 0 error、`vite build` 成功（新增 `PromptConfigView.vue` / `SceneBindingView.vue`）。
