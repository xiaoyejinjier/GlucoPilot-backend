# RabbitMQ 接入说明（基础配置）

本文记录**已完成的基础设施配置**：拓扑声明、消息转换、发送口、死信通道、自检手段。
业务事件（哪些场景发消息、消费者怎么处理）还没有接——本文最后一节写清了边界与接入方式。

- 服务器：p10 主机（`192.168.100.3`），容器 `p10-rabbitmq`（RabbitMQ **4.4.1**）
- AMQP 端口：**1186**（宿主 → 容器 5672）
- 管理台：**1187**（宿主 → 容器 15672），浏览器打开 `http://192.168.100.3:1187`
- vhost：**`glucopilot`**；账号为 `p10_admin`
- 密码**不入库**：本地放 `application-local.yaml`（已被 `.gitignore` 忽略），部署放环境变量

> p10 已经把 5672/15672 发布到宿主，所以**不需要**再起 socat 代理（与 MySQL/Redis 不同）。

---

## 1. 拓扑

| 类型 | 名称 | 参数 |
| --- | --- | --- |
| exchange（topic） | `glucopilot.event` | durable、不自动删除 |
| exchange（topic） | `glucopilot.event.dlx` | durable、不自动删除（死信专用） |
| queue | `glucopilot.event.core` | durable；`x-dead-letter-exchange=glucopilot.event.dlx`、`x-dead-letter-routing-key=glucopilot.event.core.dlq` |
| queue | `glucopilot.event.core.dlq` | durable |
| binding | `glucopilot.event --core.#--> glucopilot.event.core` | 业务事件用 `core.xxx` 路由键 |
| binding | `glucopilot.event.dlx --#--> glucopilot.event.core.dlq` | 死信全量收口 |

两条刻意的取舍：

1. **队列不设 TTL / 长度上限**。设了 `x-max-length` 或 `x-message-ttl` 会「静默丢消息」，
   对一个还没接业务的事件总线来说是最糟的默认值。容量问题交给监控与人工巡检。
2. **只有一个业务队列 + 一个死信队列**。现在没有真实消费者，先声明一条通用队列让发布口
   端到端可用；将来按消费方拆队列时，新增队列与绑定即可，发布方仍只认 `core.#` 一类路由键。

拓扑由 `RabbitMqConfig` 里的 `@Bean` 声明，Boot 自带的 `AmqpAdmin`（`RabbitAdmin`）在
建立连接后自动 `declare`，**不需要**手写 `RabbitAdmin` 调用，也不需要 `@DependsOn`。

---

## 2. 配置项

### 2.1 `spring.rabbitmq.*`（连接与客户端行为）

| 键 | 值 | 为什么 |
| --- | --- | --- |
| `host` / `port` / `username` / `password` / `virtual-host` | `${MQ_*:默认}` | 全部走环境变量，方便 p10 部署与本地联调切换 |
| `connection-timeout` | `3s` | 连不上要快速失败；MQ 不可用不能拖住整个启动 |
| `requested-heartbeat` | `30s` | 长连接空闲时保持探活，早发现半开连接 |
| `publisher-confirm-type` | `correlated` | 需要知道「代理收没收到」，配合回调留痕 |
| `publisher-returns` | `true` | 交换机上无匹配队列时消息会被退回，而不是静默丢弃 |
| `template.mandatory` | `true` | 同上：撤回必须有回调可用 |
| `template.retry.enabled` | `false` | 发送失败由业务显式决定要不要重试，避免「重试了但我们不知道」 |
| `listener.simple.acknowledge-mode` | `auto` | 方法正常返回才 ack；抛异常走重试/死信 |
| `listener.simple.prefetch` | `10` | 单消费者在途上限，防止一次拉太多把内存打满 |
| `listener.simple.concurrency` / `max-concurrency` | `2` / `8` | 默认 2 个消费者，忙时最多 8 个 |
| `listener.simple.default-requeue-rejected` | `false` | 重试耗尽后**不要**无脑 requeue（会死循环），交给死信 |
| `listener.simple.missing-queues-fatal` | `false` | 队列暂时不存在时不要阻断启动 |
| `listener.simple.retry.*` | 3 次 / 1s / ×2 / 上限 10s | 瞬时故障（数据库抖动）重试；仍失败则进死信 |

### 2.2 `glucopilot.mq.*`（业务语义）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | `false` 时**连 Bean 都不注册**（本地没有 MQ 也能起服务） |
| `exchange` | `glucopilot.event` | 业务交换机 |
| `dead-letter-exchange` | `glucopilot.event.dlx` | 死信交换机 |
| `queue` | `glucopilot.event.core` | 业务队列 |
| `queue-routing-key` | `core.#` | 业务队列绑定的路由键模式 |
| `dead-letter-queue` | `glucopilot.event.core.dlq` | 死信队列 |
| `dead-letter-routing-key` | `#` | 死信队列绑定（全收） |

它与 `spring.rabbitmq.*` 分开的理由：前者是「怎么连」，后者是「我们的拓扑叫什么」。
换拓扑不该动连接参数，换集群不该动业务命名。

---

## 3. 本地联调

`glucopilot-business/src/main/resources/application-local.yaml`（已 gitignore）：

```yaml
spring:
  rabbitmq:
    host: 192.168.100.3
    port: 1186
    username: p10_admin
    password: <向运维要，或从部署环境变量读>
    virtual-host: glucopilot
```

启动：`java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev,local --server.port=8091`

连上之后日志会出现（`INFO o.s.a.r.c.CachingConnectionFactory`）：

```
Attempting to connect to: [192.168.100.3:1186]
Created new connection: rabbitConnectionFactory#...:0/SimpleConnection@... [delegate=amqp://p10_admin@192.168.100.3:1186/glucopilot, localPort=...]
```

> 连接是**懒建立**的：`RabbitAdmin` 在第一次需要时才连（例如健康检查、第一次发送）。
> 因此「启动日志里没看到连接」不代表配置没生效。

---

## 4. 发送

```java
@RequiredArgsConstructor
class SomeService {
    private final MqPublisher publisher;

    void onSomethingCreated(SomeEvent event) {
        String messageId = publisher.publish("core.something.created", event);
    }
}
```

`MqPublisher.publish(routingKey, payload)` 会：

1. 写消息头 `messageId`（UUID，**消费方用它做幂等键**）、`timestamp`、`x-source=glucopilot-backend`；
2. 用 `CorrelationData` 发送，确认失败与「无队列可路由」都只 WARN（异步回调抛不回调用方）；
3. 返回 `messageId` 给调用方落库/打日志。

失败语义（都不静默吞）：

| 情况 | 行为 |
| --- | --- |
| `glucopilot.mq.enabled=false` | 抛 `BizException(SERVICE_UNAVAILABLE, "消息通道未启用…")` |
| 没有 `RabbitTemplate`（没装 starter / 自动配置被关） | 同上 |
| 发送时抛 `AmqpException`（连不上、vhost 不存在…） | 收敛成 503，不暴露底层堆栈 |

**为什么「不静默丢弃」**：静默丢弃会让业务方以为发送成功（例如「已通知」），
而实际什么都没发生。宁可让调用方拿到 503 决定降级。

---

## 5. 消费

```java
@RabbitListener(queues = "${glucopilot.mq.queue}")
void onEvent(SomeEvent event, Message raw) {
    // 用 raw.getMessageProperties().getMessageId() 做幂等
}
```

三条约定：

1. **参数类型直接声明事件类**。`@RabbitListener` 会把方法参数类型以
   `ParameterizedTypeReference` 形式传给转换器，类型由此确定。
2. **不要依赖消息头 `__TypeId__`**。Spring AMQP 的可信包白名单是**整包精确匹配**
   （源码是 `packageName.equals(trustedPackage)`，不是前缀匹配），要放开就得逐包枚举，
   等于允许「谁能发消息谁就能让消费者加载任意类」。所以本项目不放开白名单；
   需要无类型读取时按 `Map` 接收。
3. **幂等由消费方负责**：至少一次投递，重复是常态。用 `messageId` 落一张去重表，
   或把业务写操作设计成幂等（唯一键 + upsert）。

异常与死信：

- 方法抛异常 → `acknowledge-mode=auto` 不会 ack → `listener.simple.retry` 重试 3 次（1s、2s、4s）；
- 仍失败且 `default-requeue-rejected=false` → 消息被 reject 且 requeue=false →
  由队列参数转发到 `glucopilot.event.dlx` → 落到 `glucopilot.event.core.dlq`，
  消息头里能看到 `x-death`（原队列、原因、次数、原路由键）。

---

## 6. 死信处置

死信队列不是垃圾桶，是**待排查队列**。处置顺序：

1. 管理台 → Queues → `glucopilot.event.core.dlq` → Get messages（**不要** Ack 之前先看清 `x-death`）；
2. 判断是「消息本身有问题」还是「消费者当时的故障」：
   - 消费者故障（数据库 downtime）：修好后把消息重新投回原交换机，路由键取 `x-death[0].routing-keys` 里的值；
   - 消息本身有问题（字段缺失、版本不兼容）：修数据后重投，或记录后丢弃并清理；
3. 重投用管理台 Publish message（填 exchange `glucopilot.event` 与原路由键、payload 原样粘贴），
   或写脚本批量重投；重投前确认消费方幂等，否则会重复处理。

管理 API 里也能看到队列状态：

```powershell
$h = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("p10_admin:<密码>")) }
(Invoke-WebRequest "http://192.168.100.3:1187/api/queues/glucopilot" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json |
  Select-Object name, messages, consumers
```

> PowerShell 里 `-UseBasicParsing` 返回的 `Content` 已经是字符串，
> **不要**再 `[Text.Encoding]::UTF8.GetString($r.Content)`（会报「无法将 String 转为 Byte[]」）。

---

## 7. 自检

| 检查 | 做法 |
| --- | --- |
| 应用健康 | `GET http://127.0.0.1:8091/actuator/health` → `status: UP`（含 `rabbit` 组件时说明连得上） |
| 拓扑存在 | 管理台 Exchanges/Queues 里看到第 1 节的两交换机两队列；或上面的 API |
| 端到端往返 | `MQ_INTEGRATION=true mvn -pl glucopilot-business -am -Dtest=RabbitMqIntegrationTest test`（需 `MQ_HOST/MQ_PORT/MQ_USERNAME/MQ_PASSWORD/MQ_VHOST`），两例分别验证「发送→接收」与「nack→死信 + x-death」 |
| 单测（不连 MQ） | `mvn -pl glucopilot-business -am -Dtest=RabbitMqConfigTest,MqPublisherTest test` |

集成测试为什么默认跳过：它会在共享的 p10 上真的收发消息，进 CI 会让「没有 MQ 的环境」永远红。

---

## 8. 边界与后续

已做：连接配置、拓扑声明、JSON 转换（Long 不做字符串化）、发送口（含确认/退回留痕）、
死信通道、两条绑定、单测 9 个 + 集成测试 2 个。

**没做**（按需再上，避免空转）：

- 业务事件接入：目前没有任何 `publish` 调用点；
- 事务性发件箱（outbox）：现在是「先写库再发消息」，极端情况下可能「库成功、消息失败」，
  需要对账或 outbox 才能彻底消除；届时用同一 `messageId` 做补偿；
- 消费端骨架：没有 `@RabbitListener`，也没有消费失败的重投脚本；
- 监控：没开 prometheus 插件、没配队列积压告警（`/api/queues/.../messages` 超过阈值应告警）；
- 多租户/环境隔离：现在所有环境共用一个 vhost `glucopilot`，本地联调会与 p10 上其它服务
  抢同一条队列——正式接入业务前建议按环境拆 vhost 或拆路由键前缀。

> 上面三条里，「事务性发件箱」「消费端骨架」「vhost 隔离」在**任务队列**（第 9 节）里已经各有对策：
> 补扫替代 outbox、`JobDispatcher` + manual ack 的容器工厂替代手写消费端、本机联调改用 `glucopilot-dev`。
> 通用事件队列（`glucopilot.event`）本身仍然保持这节描述的状态。

---

## 9. AI 后台任务队列（`glucopilot.job`）

这一节是给「AI 后台任务」用的独立拓扑，与第 1 节的通用事件队列**互不影响**。
设计动机：审核 / 记忆抽取 / OCR 这三件事的共同点是「DB 里有状态、MQ 只负责通知」，
所以它们要的是**可靠派发 + 有限重试 + 按域分桶的死信**，而不是事件广播。

### 9.1 拓扑

| 名称 | 类型 | 绑定 | 说明 |
| --- | --- | --- | --- |
| `glucopilot.job` | topic exchange (durable) | — | 任务交换机 |
| `glucopilot.job.dlx` | topic exchange (durable) | — | 任务死信交换机 |
| `glucopilot.job.moderation` | queue (durable) | `job.moderation.#` | 文章/评论审核 |
| `glucopilot.job.memory` | queue (durable) | `job.memory.#` | 会话结束后的长期记忆抽取 |
| `glucopilot.job.ocr` | queue (durable) | `job.ocr.#` | 图片 OCR |
| `glucopilot.job.<域>.dlq` | queue (durable) | `#`（在 dlx 上） | 各自失败消息，按域隔离 |

三个业务队列都带 `x-dead-letter-exchange=glucopilot.job.dlx` 与自己的死信路由键，**不设 TTL 与长度上限**：
任务积压时宁可堆积也不要静默丢（丢弃是显式的 `basicNack(requeue=false)` 决定的结果）。

### 9.2 消息信封

```json
{
  "jobId": "MODERATION_ARTICLE:123:r2",
  "type": "MODERATION_ARTICLE",
  "refId": 123,
  "revision": 2,
  "userId": 5,
  "reason": "publish",
  "attempt": 0,
  "createdAt": "2026-11-24T10:00:00Z"
}
```

- **只放标识，不放正文**：审核正文、会话、图片都在库里，消费时按 `refId` 取当前内容；
  这样消息里不会出现用户内容（合规面小），也不会出现「改稿后审旧文本」。
- `jobId` 同时写消息头 `messageId`：管理台「消息详情」与业务日志用同一个串对得上。
- `revision` 只有审核带，用来做乐观并发（同一篇文章的第 2 次审核与第 1 次是两条任务）。
- `attempt` 是**已经失败过的次数**（首次投递为 0），由重投器递增，用于判定上限。

### 9.3 三态与 ack 策略

| 结论 | 谁产生 | 动作 |
| --- | --- | --- |
| `SUCCESS` | 处理器返回 / 认领失败 | `basicAck` |
| `RETRYABLE` | 处理器返回 / 未捕获异常 | `basicNack(requeue=false)` → DLX → 延迟重投 |
| `PERMANENT` | 处理器返回 / 无处理器 / 类型未知 | `basicAck`（落终态，不再重投） |

刻意不用 `requeue=true`：它把消息放回**队头**，一条坏消息能堵住整个队列。
重试一律走「死信收集 → 60 秒后重新发」，等价于排到队尾。

### 9.4 两套兜底

- **补扫**（`JobReconciler`，默认 300 秒一次，单域上限 200 条）：把库里「还等着办」的任务再投一遍。
  覆盖四种丢消息的窗口：afterCommit 发送失败、broker 丢消息、消费端 ack 前被杀、有人直接改库。
  各域实现 `JobReconcileSource`，判据分别是：
  - 审核：`ai_status='PENDING' AND ai_claimed_at IS NULL AND gmt_create < NOW(3) - 1 minute`
  - 记忆：`session_status=ENDED AND (memory_extracted_upto_msg_id IS NULL OR < 最后一条消息 id)`
  - OCR：`task_status=PENDING AND gmt_create < NOW(3) - 1 minute`
- **死信重投**（`JobRetryReplayer`，默认 60 秒一轮）：从三个 `.dlq` 取消息，`attempt+1 <= max-attempts`（默认 3）
  就重新发布；超限则打一条带完整信封的 WARN 后丢弃——任务真相在库里，人工处置看业务记录 + jobId。

### 9.5 配置（`glucopilot.mq.jobs.*`）

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `true` | 任务队列总开关（false 时不注册拓扑/监听器/补扫/重投） |
| `event-driven` | `true` | 消息是否为主通道；false 时业务回退到轮询/线程池（灰度回退开关） |
| `reconcile-enabled` | `true` | 补扫开关 |
| `reconcile-interval-seconds` | `300` | 补扫间隔 |
| `reconcile-initial-delay-seconds` | `60` | 启动后第一次补扫的延迟（避免启动瞬间抢任务） |
| `reconcile-batch-size` | `200` | 单域单轮上限 |
| `replay-interval-ms` | `60000` | 死信重投间隔（同时是重试间隔） |
| `max-attempts` | `3` | 最多重投次数（不含首次） |
| `moderation-concurrency` / `memory-concurrency` / `ocr-concurrency` | `4` / `2` / `2` | 三个域的消费者并发 |
| `prefetch` | `2` | 预取条数（任务耗时长，预取多会让别的实例空等） |

### 9.6 当前进度

- **批次 0（已完成）**：公共骨架 + 拓扑 + 配置 + 单测。
  `JobEnvelope` / `JobTypes` / `JobOutcome` / `JobHandler` / `JobDispatcher` / `JobPublisher` /
  `JobReconciler`（无域实现）/ `JobRetryReplayer` / `JobMetrics` / `RabbitJobConfig`（含 manual ack 容器工厂）。
  **还没有 `@RabbitListener`**：三个队列已在 p10 上声明，但暂时没有消费者，也没有任何 `publish` 调用点。
- 批次 1~3 依次接入审核、记忆抽取、OCR（每个处理器实现 `JobHandler` + 一个 `JobReconcileSource`，
  并加 `@RabbitListener(queues = ..., containerFactory = "jobContainerFactory", concurrency = ...)`）。
- 批次 4 是翻译的补丁（拒绝时回 PENDING + 扫过期租约），不换 MQ。

### 9.7 自检

```powershell
# 交换机 / 队列 / 绑定（本地 vhost = glucopilot-dev）
$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("p10_admin:<密码>"))
Invoke-RestMethod "http://192.168.100.3:1187/api/exchanges/glucopilot-dev" -Headers @{Authorization=$auth} |
  Where-Object { $_.name -like "glucopilot.job*" }
Invoke-RestMethod "http://192.168.100.3:1187/api/queues/glucopilot-dev" -Headers @{Authorization=$auth} |
  Select-Object name, messages, consumers
```

