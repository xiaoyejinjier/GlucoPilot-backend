# GlucoPilot Backend · Common 类制作文档

> 文档版本：V1.0  
> 日期：2026-09-16  
> 依据：《接口设计文档》20260914-01-01、《数据库设计文档》20260914-01-02、`intelligent_health_assistant.sql`（38 表）、`pom.xml`（Spring Boot 4.1.1 / Java 21）  
> 目的：指导 `com.threadfit.glucopilot.common` 包的首期落地，保证各业务模块共用同一套响应、错误、分页、审计与安全底座。

---

## 1. 目标与边界

### 1.1 Common 做什么

| 职责 | 说明 |
|------|------|
| 统一响应与错误模型 | `{ code, message, data, requestId }`；HTTP 三位 + body.code 五位 |
| 分页契约 | `pageNum/pageSize/total/pages/records`，`pageSize` 最大 100 |
| 持久化底座 | 审计字段、逻辑删除、MyBatis-Plus 自动填充 |
| 请求上下文 | `requestId`、当前 C/B 用户、幂等键 |
| 横切能力 | 全局异常、参数校验、JSON 序列化、常用常量/枚举 |

### 1.2 Common 不做什么

- 不写具体业务 Service（血糖、饮食、OCR、聊天等）
- 不直接依赖微信 SDK、短信供应商、大模型 SDK 的业务实现
- 不在 common 中硬编码运营 RBAC 权限码业务逻辑（权限码常量可放 common，鉴权规则在 security / admin 模块）
- **不以** `MVP_API_V1.md` 为基线（该草案与生产文档不一致）；以两份 docx + SQL 为准

### 1.3 双端与前缀（接口文档）

| 端 | Base Path | 会话表 | 鉴权 |
|----|-----------|--------|------|
| C 微信小程序 | `/api/v1` | `user_session` | Bearer Token |
| B 运营后台 | `/admin/v1` | `ops_session` + RBAC `perm_code` | Bearer Token |

---

## 2. 技术选型约束（Maven）

来自 `backend/pom.xml`，common 类选型必须与之对齐：

| 能力 | 依赖 | Common 用法 |
|------|------|-------------|
| Web / MVC | `spring-boot-starter-web` | `@RestControllerAdvice`、Filter/Interceptor |
| 校验 | `spring-boot-starter-validation` | `@Valid`、`MethodArgumentNotValidException` → `40000` |
| 安全 | `spring-boot-starter-security` | 过滤器链入口；Principal 由 security 模块填充，common 只提供上下文读取 API |
| ORM | `mybatis-plus-spring-boot4-starter` 3.5.17 | `BaseEntity`、`MetaObjectHandler`、逻辑删除 |
| Redis | `spring-boot-starter-data-redis` | `requestId` 可选落库；`X-Idempotency-Key` 24h 缓存 |
| JWT | `jjwt` 0.13.0 | Token 解析工具放 `common.security` 或 `security` 模块；会话权威以 DB `is_valid` 为准 |
| 文档 | `springdoc-openapi` 3.1.0 | 统一响应/分页 schema 注解 |
| 工具 | Lombok | `@Data` / `@Builder`；禁止过度 Builder 套娃 |
| 存储 | MinIO 8.6.0 | **不在 common**；媒体客户端在 `infra` / `media` 模块 |
| 迁移 | Flyway | **不在 common** |

Java 版本：**21**。包根：`com.threadfit.glucopilot`。

---

## 3. 推荐包结构

```text
com.threadfit.glucopilot
├── GlucoPilotBackendApplication
├── common
│   ├── api                 # 统一响应、分页、错误码
│   ├── exception           # 业务异常、全局处理
│   ├── model               # BaseEntity、审计字段
│   ├── enums               # 跨模块数字枚举（与 DDL COMMENT 一致）
│   ├── context             # RequestContext / UserContext / OpsContext
│   ├── web                 # RequestIdFilter、IdempotencyFilter（或 Advice）
│   ├── mybatis              # MetaObjectHandler、类型处理器
│   ├── util                # JsonUtils、IdUtils、Asserts
│   └── constant            # 头名称、Redis Key 前缀
├── auth / user / glucose / meal / media / ocr / chat / cms / admin ...
└── config                  # Security、Redis、MyBatis、OpenAPI 装配（可引用 common）
```

业务模块按 SQL 域划分（与数据库文档模块清单对齐）：

| 模块域 | 代表表 | 说明 |
|--------|--------|------|
| 字典 | `dict_glucose_node` | 九时段 |
| C 用户 | `user_*`、`auth_sms_code` | 登录/资料/健康/设置/会话 |
| 运营 RBAC | `ops_*`、`sys_*` | 账号、角色、权限、会话 |
| 健康记录 | `glucose_record`、`meal_record`、`meal_food_item` | 分表 + 日看板聚合 |
| 媒体 / OCR | `media_file`、`ocr_task` | 先登记再引用 |
| AI 对话 | `chat_*` | `clientMsgId` 幂等 + SSE |
| 内容 | `cms_*`、`food_*`、`news_user_action` | Banner/资讯/营养库 |
| 数据任务 | `data_export_task`、`data_backup` | 异步 + 幂等头 |
| 配置 / 推送 / 审计 | `sys_config`、`ai_*`、`msg_push_*`、`ops_audit_log` | B 端为主 |

### 3.1 MyBatis / 实体结构性约定（易踩坑）

| 约定 | 正确 | 错误（已踩过） |
|------|------|----------------|
| `@MapperScan` | 只扫 `*.mapper`，并设 `markerInterface = BaseMapper.class` | 扫 `com.threadfit.glucopilot` 全包 → `port`/`client`/`CodeEnum` 被当成 Mapper → `Invalid bound statement` |
| 业务端口 / 客户端 | `*.port`、`*.client` 普通 Spring 接口 + `@Service`/`@Component` 实现 | 与 Mapper 混在同一扫描根下 |
| 实体字段 ↔ DDL | 数字驼峰显式 `@TableField`，如 `carbsPer100g` → `carbs_per_100g` | 依赖默认驼峰 → `carbs_per100g`（少 `_`）列不存在 |
| Mapper 接口 | 放 `*.mapper`，继承 `BaseMapper<Entity>`，可加 `@Mapper` | 把 Port 当 Mapper 注入 |

模块内建议目录：`entity` / `mapper` / `service` / `controller`；模块内跨层写入可用 `port`（如 device 包内 `GlucoseIngestPort`），**绝不**放进 MapperScan 范围。

---

## 4. 必须实现的 Common 类清单

### 4.1 统一响应 — `common.api`

#### `ApiResponse<T>`

对齐接口设计规范：

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "requestId": "b1f0c8e2-4a3d-4c1e-9f0a-123456789abc"
}
```

| 字段 | 类型 | 规则 |
|------|------|------|
| `code` | `int` | 成功固定 `0`；失败为五位业务码 |
| `message` | `String` | 对用户可读短文案 |
| `data` | `T` | 失败时可为 `null` |
| `requestId` | `String` | 与请求头/MDC 同源，便于追踪 |

建议工厂方法：

- `ApiResponse.ok(T data)`
- `ApiResponse.ok()` → `data=null`
- `ApiResponse.fail(ErrorCode code)` / `fail(ErrorCode, String message)` / `fail(int code, String message)`

> 注意：废弃 MVP 文档中的字符串 `code`（如 `VALIDATION_FAILED`）与 `traceId` 字段名；生产契约使用 **数字 code + `requestId`**。

#### `ErrorCode`（枚举）

| HTTP | code | 枚举建议名 | 含义 |
|-----:|-----:|------------|------|
| 200 | 0 | `SUCCESS` | 成功 |
| 400 | 40000 | `BAD_REQUEST` | 参数格式/类型/必填失败 |
| 400 | 40001 | `BIZ_REJECTED` | 业务校验失败或凭证错误（验证码错误统一此码，防枚举） |
| 401 | 40100 | `UNAUTHORIZED` | 未登录 / Token 失效 |
| 403 | 40300 | `FORBIDDEN` | 无权限、禁用、注销中 |
| 404 | 40400 | `NOT_FOUND` | 不存在或不属于当前用户 |
| 409 | 40900 | `CONFLICT` | 唯一键、占用、乐观冲突 |
| 429 | 42901 | `TOO_MANY_REQUESTS` | 限流 / 登录锁定 |
| 500 | 50000 | `INTERNAL_ERROR` | 系统异常 |

每个枚举项携带：`httpStatus`、`code`、`defaultMessage`。  
后续细分只增同族五位码（如 `40901`），并同步改本枚举与接口文档错误码总表。

#### `PageQuery`

| 字段 | 默认 | 约束 |
|------|------|------|
| `pageNum` | 1 | ≥ 1 |
| `pageSize` | 20 | 1～100 |

提供 `offset()` / 转 MyBatis-Plus `Page` 的方法。

#### `PageResult<T>`

分页 `data` 固定结构：

```json
{
  "pageNum": 1,
  "pageSize": 10,
  "total": 128,
  "pages": 13,
  "records": []
}
```

提供 `of(IPage<T>)` 从 MyBatis-Plus 转换。字典、Banner、食物分类等接口返回**全量 List**，不要套 `PageResult`。

---

### 4.2 异常体系 — `common.exception`

| 类 | 职责 |
|----|------|
| `BizException` | 携带 `ErrorCode`（或 int code + httpStatus）+ message + 可选 `data`（如字段错误列表） |
| `GlobalExceptionHandler` | `@RestControllerAdvice`：校验异常→`40000`；`BizException`→对应码；`AccessDenied`→`40300`；`Authentication`→`40100`；其它→`50000`（日志记堆栈，响应不泄细节） |

校验失败建议 `data` 形态（可选，便于前端标红）：

```json
{
  "fields": [
    { "field": "phone", "message": "must not be blank" }
  ]
}
```

规则：

- **禁止** HTTP 200 包业务失败
- **禁止**「未实现」类业务码（如已废除的 `50100`）
- SSE（`text/event-stream`）错误通道由 chat 模块单独约定，勿强行包成 JSON `ApiResponse` 打断流

---

### 4.3 持久化底座 — `common.model` + `common.mybatis`

#### `BaseEntity`（业务表通用）

对齐数据库设计规范与 DDL 审计列：

| Java 字段 | DB 列 | 说明 |
|-----------|-------|------|
| `id` | `id` | `Long`，`BIGINT UNSIGNED`；**JSON 序列化为字符串**，避免 JS 精度丢失 |
| `createdBy` | `created_by` | 默认 0 |
| `gmtCreate` | `gmt_create` | `LocalDateTime`，精度到毫秒 |
| `updatedBy` | `updated_by` | 默认 0 |
| `gmtModified` | `gmt_modified` | ON UPDATE；应用层勿覆盖为旧值 |
| `deletedBy` | `deleted_by` | 逻辑删除人 |
| `deletedAt` | `deleted_at` | 逻辑删除时间 |
| `isDeleted` | `is_deleted` | 0/1；MyBatis-Plus `@TableLogic` |

CHECK 一致性（应用层删除时必须成对写）：  
`is_deleted=0` ⇒ `deleted_at/deleted_by` 为空；`is_deleted=1` ⇒ `deleted_at` 非空。

#### `AppendOnlyEntity`（仅追加表）

用于 `ops_audit_log`：仅 `createdBy` + `gmtCreate`，**无** `gmt_modified` / 逻辑删除字段。不要继承完整 `BaseEntity`。

#### `AuditMetaObjectHandler`

插入时填充 `gmtCreate`、`gmtModified`、`createdBy`、`updatedBy`；更新时填充 `gmtModified`、`updatedBy`。  
操作人从 `RequestContext` 取：C 端 `userId`，B 端 `opsId`，匿名写操作为 `0`。

#### ID 与 JSON

- 实体主键：`Long`
- 对外 JSON：`Long` → **字符串**（Jackson `ToStringSerializer` 或全局模块配置）
- 入参：允许字符串数字反序列化为 `Long`

---

### 4.4 请求上下文 — `common.context` + `common.web`

#### `RequestContext`（ThreadLocal / ScopedValue）

| 属性 | 来源 |
|------|------|
| `requestId` | 入站头 `X-Request-Id`，无则 UUID；写入 MDC 与响应体 |
| `userId` | C 端鉴权后 |
| `opsId` | B 端鉴权后 |
| `loginType` | 可选，对应 `user_session.login_type` |
| `clientIp` / `userAgent` | 写会话、审计用 |
| `idempotencyKey` | 头 `X-Idempotency-Key` |

请求结束必须 `clear()`，防止线程池泄漏。

#### `RequestIdFilter`

1. 解析或生成 `requestId`  
2. 放入 Context + MDC（`requestId`）  
3. 响应头回写 `X-Request-Id`  
4. `ApiResponse` 组装时读取同一值  

#### 幂等（能力放 common，策略分场景）

| 场景 | 键 | Common 支持 |
|------|-----|-------------|
| 聊天发消息 | Body `clientMsgId` | 工具方法 + 捕获 DB 1062 转成功的约定说明（实现可在 chat 模块） |
| 九时段写 | `PUT .../by-node` 自然幂等 | 业务模块；common 提供日期+node 校验工具即可 |
| 导出/备份/OCR 创建、B 端发布/回复/推送 | 头 `X-Idempotency-Key` | `IdempotencyService`：Redis 存 24h，同键返回首次结果 |

常量：

```text
Header: Authorization, X-Request-Id, X-Idempotency-Key
Redis: glucopilot:idem:{scope}:{key}  TTL=86400
```

---

### 4.5 跨模块枚举 — `common.enums`

原则（数据库文档）：**TINYINT + COMMENT；API 传数字；中文仅 UI**。  
Common 只放**多模块共用**枚举；冷门业务枚举可放各模块。

建议首批：

| 枚举 | 对应 DDL | 值 |
|------|----------|-----|
| `AccountStatus` | `user_account.account_status` | 0 禁用 / 1 正常 / 2 注销中 |
| `OpsAccountStatus` | `ops_account.account_status` | 0 禁用 / 1 正常 |
| `Gender` | `user_profile.gender` | 0 未知 / 1 男 / 2 女 |
| `DiabetesType` | `user_health.diabetes_type` | 0～4 |
| `LoginType` | `user_session.login_type` | 1 微信 / 2 手机号 |
| `GlucoseSourceType` | `glucose_record.source_type` | 1 手动 / 2 OCR / 3 设备 |
| `MediaBizType` | `media_file.biz_type` | 1～6 |
| `OcrBizType` / `TaskStatus` | `ocr_task` | biz 1血糖2餐食；status 0～3 |
| `YesNo` | 各类 0/1 开关 | 0 / 1 |

枚举实现建议：`code()` 返回 int；`of(int)` 非法值抛 `BizException(40000)`。  
九时段 `node_code` 是**字符串字典**（`before_breakfast` 等），用常量类 `GlucoseNodes` + 校验「血糖节点 is_meal=0 / 饮食节点 is_meal=1」，不要做成 1～9 硬编码枚举。

---

### 4.6 工具与断言 — `common.util`

| 类 | 用途 |
|----|------|
| `Asserts` | `notNull` / `isTrue` → 失败抛 `BizException`（指定 ErrorCode） |
| `JsonUtils` | 基于 Jackson 的 toJson/fromJson（与 Boot 同一 ObjectMapper） |
| `IdUtils` | UUID、可选雪花（若不用 DB 自增以外 ID） |
| `IpUtils` | 解析 `X-Forwarded-For` / `X-Real-IP` |
| `SqlDuplicateHelper` | 判断 MySQL 1062，供聊天幂等复用 |

---

## 5. 与功能模块的协作约定

```text
Controller  → 只依赖 ApiResponse / PageQuery / 入参 DTO
Service     → 抛 BizException；读写 Entity（继承 BaseEntity）
Security    → 鉴权成功后写入 RequestContext；失败走 40100/40300
Infra       → Redis/MinIO/微信；common 只定义接口或轻量客户端包装（可选）
```

### 5.1 C 端核心链路（接口文档）对 Common 的依赖

| 能力 | 接口方向 | Common 点 |
|------|----------|-----------|
| 登录会话 | 微信/短信会话 | Token 工具 + 会话 `is_valid` 校验约定 |
| 用户 me/profile/health/settings | CRUD | `ApiResponse`、审计填充 |
| 媒体 | 凭证 + 登记 | 无业务表外键；只传 `mediaId` |
| 日看板 / 血糖 / 饮食 | 读聚合、写 by-node | 分页/全量约定、节点校验工具 |
| OCR / 导出 / 备份 | 异步任务 | `X-Idempotency-Key` + `TaskStatus` |
| 聊天 | SSE + `clientMsgId` | 幂等约定；SSE 不走统一 JSON 包装 |

### 5.2 B 端

- 登录失败统一 `40001`（防账号枚举）；停用 `40300`
- 写操作与健康档案查看：业务模块写 `ops_audit_log`（可用 common 的 `AuditLogWriter` 门面，可选一期）
- RBAC：`perm_code` 常量可集中在 `common.constant.PermCodes`，与 SQL 种子一致（如 `health:archive:view`）

### 5.3 安全与会话（接口规范摘要）

| 项 | 约定 |
|----|------|
| C Token | 默认 7 天，**无 refreshToken** |
| B Token | 默认 2 小时，可滑动续期 |
| 失效 | 会话行 `is_valid=0`（非黑名单为主方案） |
| C 端 | **无密码体系**；运营口令只存 `password_hash` |
| 枚举 | API 数字；禁止英文大写字符串枚举对外 |

---

## 6. 推荐落地顺序

```text
进度：
- [ ] 1. ApiResponse + ErrorCode + BizException + GlobalExceptionHandler
- [ ] 2. RequestIdFilter + RequestContext + MDC
- [ ] 3. PageQuery / PageResult + OpenAPI schema
- [ ] 4. BaseEntity + MetaObjectHandler + Long 序列化字符串
- [ ] 5. 首批 common.enums + Asserts
- [ ] 6. IdempotencyService（Redis，24h）
- [ ] 7. （可选）AuditLogWriter、PermCodes
```

每步完成后用一个最小 `@RestController`（如 `GET /api/v1/healthz` 或公开配置）验证：成功体、校验失败 `40000`、`requestId` 回传。

---

## 7. 编码规范（Common 专属）

1. **类名**：响应用 `ApiResponse`，不用 `R`/`Result` 过度缩写（文档与 OpenAPI 可读性优先）。  
2. **时间**：对外 `yyyy-MM-dd'T'HH:mm:ss.SSS` 或与接口文档示例统一的 ISO 本地时间；DB `DATETIME(3)`。  
3. **空值**：`PATCH` 省略=不改，显式 `null`=置空；common 的 JSON 配置勿全局 `NON_NULL` 到破坏置空语义的程度（可按 DTO 使用 `@JsonInclude`）。  
4. **日志**：错误日志带 `requestId`；禁止打印 Token、验证码、password、AI Key。  
5. **依赖方向**：`common` ← 业务模块；`common` 不依赖业务包。  
6. **测试**：为 `ErrorCode` 映射、`PageQuery` 边界（pageSize=100/101）、`GlobalExceptionHandler` 写单测。

---

## 8. 类—契约对照速查

| 接口/库契约 | Common 类 |
|-------------|-----------|
| `{ code, message, data, requestId }` | `ApiResponse` |
| HTTP + 五位 code 表 | `ErrorCode` + `GlobalExceptionHandler` |
| `pageNum/pageSize/total/pages/records` | `PageQuery` / `PageResult` |
| `gmt_*` / `is_deleted` / `created_by`… | `BaseEntity` + `AuditMetaObjectHandler` |
| `ops_audit_log` 只追加 | `AppendOnlyEntity` |
| `X-Request-Id` / 追踪 | `RequestIdFilter` + `RequestContext` |
| `X-Idempotency-Key` 24h | `IdempotencyService` |
| TINYINT 枚举数字 | `common.enums.*` |
| BIGINT 对外字符串 | Jackson Long 配置 |

---

## 9. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| V1.0 | 2026-09-16 | 首版：综合接口设计 docx、数据库设计 docx、38 表 SQL、Maven 选型与功能模块边界 |

---

## 10. 附录：与旧 MVP 文档的差异（避免做错）

| 项 | 生产基线（docx + SQL） | 勿采用（MVP_API_V1） |
|----|------------------------|----------------------|
| 响应 code | 数字 `0` / `4xxxx` | 字符串 `SUCCESS` 等 |
| 追踪字段 | `requestId` | `traceId` |
| 分页 | `pageNum` + `records` | `page` + `items` |
| 登录 | 微信 / 短信，C 端无密码 | 手机号+密码 |
| 血糖模型 | 九时段 `node_code` + 日看板 | `mealRelation` POST_30/60/120 |
| 预测 | `meal_record.predict_*` 字段 | 独立 `prediction_record` 表 |

实现 common 时若发现与 `MVP_API_V1.md` 冲突，**以本文件与两份 docx 为准**。
