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
| `project/tools/sse-check/`（新增） | SSE 解析自检脚本 + 真实响应夹具（见 14.4） |

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

小程序端无法在 CI 里跑，但 SSE 解析是**最容易静默出错**的一段，因此把后端真实响应抓成夹具，在 Node 里把 `openSse` 的分片按任意粒度重放（`project/tools/sse-check/`，用法见其中 README）：

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
  建议第一次联调时按 `tools/sse-check/README` 重新抓一次夹具，顺便确认 17.2 的编码在真实链路上同样正确。

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







