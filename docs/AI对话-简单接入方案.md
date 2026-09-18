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

yaml 写的是 `spring.ai.deepseek.chat.model`，但 Spring AI 的 DeepSeek 配置类 `DeepSeekChatProperties` 的前缀是 `spring.ai.deepseek.chat`，其下还有一层 `options`：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        options:
          model: deepseek-chat        # ← 正确路径是 chat.options.model
          temperature: 0.7
```

写错不会报错，只会**静默回落到默认模型**（`deepseek-chat`），排查起来很费时间。

> 已扫描 `spring-ai-autoconfigure-model-deepseek-2.0.1.jar` 确认前缀就是 `spring.ai.deepseek.chat`，且 `DeepSeekChatProperties` 内嵌 `Options` 类。

## 3.3 【P1】模型名 `deepseek-v4-flash` 需要确认

`spring-ai-deepseek-2.0.1.jar` 里 `DeepSeekApi$ChatModel` 只枚举了 **`deepseek-chat`** 与 **`deepseek-reasoner`**；默认 base-url 是官方 `https://api.deepseek.com`。

`model` 字段本身是字符串，能填任意值，但官方端点不认的模型名会返回 **HTTP 400**。所以要么改成 `deepseek-chat`/`deepseek-reasoner`，要么确认你要接的是**第三方中转/聚合网关**（那就同时要配 `base-url`）。**这一条必须在 L0 冒烟第一枪就打真实调用确认。**

## 3.4 【P0-安全】API Key 明文写在 `application.yaml`

当前 yaml 里是明文的 `sk-7bde…`（未提交，但随时可能被 `git add .` 带进去）。

- 改成 `${DEEPSEEK_API_KEY:}`，本地值放 `application-local.yaml` 并加入 `.gitignore`；或直接走系统环境变量。
- **如果这个 Key 曾经提交过 git，请去 DeepSeek 控制台轮换。**（顺带确认额度/余额）
- 日志里要过滤 `Authorization` / `api-key`，避免请求日志把 Key 打出来。

## 3.5 【P2】`builder.defaultAdvisors()` 是空调用

`defaultAdvisors(Advisor... advisors)` 是可变参数，不传参数等于**什么都没做**，留着会误导后来人以为已经挂了记忆/审计。先删掉，等真要用 `MessageChatMemoryAdvisor` 时再挂。

## 3.6 【P2】两个 0 字节提示词文件

`promotes/agent/memory_chat.system.md`、`tool_call_prompt.system.md` 都是 0 字节。要么填内容，要么删掉，避免以后读到空系统提示词导致"模型像换了个人"。

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



