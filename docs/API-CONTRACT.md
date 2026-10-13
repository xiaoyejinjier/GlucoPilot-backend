# API 契约

## 官方若依后端迁移约定（2026-10-09）

后端改为官方 RuoYi-Vue 多模块工程，由 `ruoyi-admin` 启动。所有原有路径、普通响应成功码 0、分页字段和 SSE 事件继续保持。管理端登录返回的 accessToken 改为若依 JWT，登录状态由 Redis 管理；旧后台会话在切换时失效，小程序 user_session 保留。运营身份与 sys_user 通过 gp_ops_identity_map 关联，既有 opsId 和审计字段语义保留。系统资源适配接口调用官方系统服务，业务分组参数独立保存于 gp_app_config。

管理端在线会话接口的 id 改为若依会话 UUID 字符串（客户端仅用于显示与强退路径）；其他字段继续保持。强退请求仍为 DELETE /admin/v1/ruoyi/online/{id}。账号禁用、密码和权限变更必须撤销或刷新受影响的 Redis 会话。新建运营账号写入若依账号并建立业务身份映射。

## 管理端 C 端用户管理

管理端“用户管理”页面必须读取业务库中的 C 端用户，不得回退到前端 Mock 数据，也不得与若依运营账号 `/admin/v1/ruoyi/users` 混用。

| 方法与路径 | 权限 | 数据来源 | 说明 |
| --- | --- | --- | --- |
| `GET /admin/v1/users?pageNum=1&pageSize=100&keyword=&accountStatus=` | `user:manage` | `user_account`，关联 `user_profile`、`user_health`、`glucose_record` | 分页查询 C 端用户；`accountStatus` 可传 `0`（禁用）、`1`（正常）、`2`（注销中） |
| `PUT /admin/v1/users/{id}/status` | `user:manage` | `user_account.account_status` | 请求体为 `{ "accountStatus": 0 }` 或 `{ "accountStatus": 1 }`，用于禁用或解禁 |
| `GET /admin/v1/users/{id}/archive` | `health:archive:view` | `user_account` / `user_profile` / `user_health` / `glucose_record`（+ `meal_record` 汇总） | 健康档案详情，供管理端「健康档案」页替换 Mock 数据 |

列表返回统一的 `PageResult`，字段为 `pageNum`、`pageSize`、`total`、`pages`、`records`。每条用户记录同时返回账号表的 `username` 与资料表的 `nickname`；用户手机号仅返回脱敏值；血糖记录数与达标率取自未删除的 `glucose_record`，`mealCount` 取自未删除的 `meal_record`，不得用 Mock 数量冒充数据库数据。

档案详情 `GET /admin/v1/users/{id}/archive` 返回 `data`：`userId`、`owner`（`nickname` / `phoneMasked` 脱敏 / `age` / `diabetesTypeText` / `registeredAt`）、`profile`（`height` / `weight` / `medication` / `exerciseHabit` / `diagnosedYm`）、`trend`（近 7 日 `{date, avg, okRate}`，仅含有记录的天）、`nodeStats`（各血糖节点 `{node, nodeName, avg, samples}`，节点名取自 `dict_glucose_node`）、`mealSummary`（近 30 日 `{recordDays, avgCarbsG, avgCal, topFoods}`）与 `summary`。
**窗口口径已冻结：趋势 = 近 7 日、饮食汇总 = 近 30 日**（口径常量为 `AdminUserService` 内的 `TREND_DAYS` / `MEAL_SUMMARY_DAYS`）；变更需同步本契约与 `AdminUserServiceTest`。达标率口径为 `[3.9, 7.8)`，与用户列表 `complianceRate` 同源。
其中 `profile.height` / `profile.weight` 取自 `user_health`（`profile.health` 域，C 端 `PUT /users/me/health-profile` 写入，未填写返回 `null`），`summary`（AI 档案小结无数据源）**暂返回 `null`，为 TODO**，前端按空值展示；用户不存在时按公共 `40400` 处理。

以上接口全部返回公共 `ApiResponse { code, message, data, requestId }`。成功码为 `0`；非法状态使用公共 `40000`；用户不存在使用公共 `40400`；鉴权和系统异常继续由 `GlobalExceptionHandler` 按公共 `ErrorCode` 处理，禁止自定义另一套响应码。

## 统一入口

| 调用方 | 路径前缀 | 责任边界 |
| --- | --- | --- |
| 用户端小程序 | `/api/v1/**` | 登录、内容、营养和设备血糖同步 |
| 管理后台 | `/admin/v1/**` | 运营账号、资讯、Banner 和营养库管理 |
| 运维探针 | `/actuator/health` | 存活检查，不提供业务数据 |
| 运营权限上下文 | `/admin/v1/system/user-info`、`/admin/v1/system/menus` | 当前账号的角色、权限标识与可见菜单 |
| 若依系统管理 | `/admin/v1/ruoyi/**` | 用户、角色、菜单、部门、岗位、字典、参数、公告、日志、在线会话、任务与代码生成 |

生产环境只有 Nginx 对外开放。Nginx 将前两类接口转发给后端容器，管理端静态资源也由同一个入口提供；调用方不得绕过网关访问 `8080`。

## 响应格式

所有业务接口均返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "d7b3..."
}
```

- `code = 0` 表示成功；非零值由前端作为业务失败处理。
- `requestId` 由网关或后端生成，并贯穿后端日志；反馈问题时必须携带它。
- `Authorization: Bearer <token>` 用于受保护接口。
- `X-Idempotency-Key` 用于可能被重试的写操作；同一业务动作不得生成多个不同 key。

## 管理端权限

管理端使用 RuoYi 的权限表达式约定：后端以 `@PreAuthorize("@ss.hasPermi('资源:动作:范围')")` 保护业务操作，权限数据来自 `sys_role`、`sys_ops_role`、`sys_permission` 和 `sys_role_permission`。登录响应以及 `/admin/v1/system/user-info` 均返回当前账号的 `roles`、`permissions` 与 `menus`；前端只能用它们控制可见性，不能替代后端鉴权。

## 完整若依模块

系统管理接口统一位于 `/admin/v1/ruoyi/**`，保持项目的版本前缀和 `ApiResponse` 响应格式，不能直接暴露官方项目的无版本路径。该区域包括：

| 资源 | 路径 | 权限标识 |
| --- | --- | --- |
| 运营用户 / 角色 / 菜单 | `/users`、`/roles`、`/menus` | `system:user:manage`、`system:role:manage`、`system:menu:manage` |
| 部门 / 岗位 | `/depts`、`/posts` | `system:dept:manage`、`system:post:manage` |
| 字典 / 参数 / 公告 | `/dict-types`、`/dict-data`、`/configs`、`/notices` | `system:dict:manage`、`system:config:manage`、`system:notice:manage` |
| 监控与审计 | `/online`、`/login-logs`、`/oper-logs`、`/monitor/server` | `monitor:online:list`、`monitor:logininfor:list`、`monitor:operlog:list`、`monitor:server:list` |
| 定时任务与生成器 | `/jobs`、`/generator/tables` | `monitor:job:manage`、`tool:gen:manage` |

所有写操作都必须携带现有的运营会话令牌；在生产调用时还应传入稳定的 `X-Idempotency-Key`。任务只接受平台内置、白名单化的任务键，禁止传入任意类名或脚本作为调度命令。

## 用户反馈闭环

用户端与管理端共享 `feedback` 数据源，接口职责如下：

| 调用方 | 方法与路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| 用户端 | `POST /api/v1/feedbacks` | C 端登录 | 提交反馈，初始状态为 `0`（已收到） |
| 用户端 | `GET /api/v1/feedbacks` | C 端登录 | 只查询当前用户反馈及官方回复 |
| 管理端 | `GET /admin/v1/feedbacks` | `feedback:manage` | 分页查询全部用户反馈 |
| 管理端 | `PUT /admin/v1/feedbacks/{id}/reply` | `feedback:reply` | 更新处理状态与官方回复 |

处理状态统一为：`0` 已收到、`1` 处理中、`2` 已处理、`3` 已关闭。管理端把状态改为“已处理”时必须填写官方回复；回复写入 `official_reply` 后，用户端再次进入“我的反馈”即可通过现有查询接口看到结果。该 PUT 接口按反馈 ID 覆盖当前处理结果，重复提交相同内容不会创建新记录。

## 设备数据同步

用户端通过 `POST /api/v1/device/glucose-sync` 将血糖记录提交到同步服务。请求必须使用已登录用户的令牌，记录应带来源侧稳定的 `externalRecordId` 以便追踪重试来源；当前服务按用户、业务日期和血糖节点执行写入或更新，重复同步同一节点不会新增记录。接口修改前必须同步更新用户端调用、服务端 DTO 和测试。

## 饮食记录

用户端确认餐食后写入饮食记录与营养快照；快照在保存时点固化、不可变，不提供字段级修改（改 = 重新保存覆盖），不随营养库实时联动。同日同餐点重复保存为覆盖写入，不新增记录。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/meal-records` | C 端登录 | 保存（覆盖写入）饮食记录；`nodeCode` 须为餐点节点（`breakfast`/`lunch`/`dinner`） |
| `GET /api/v1/meal-records?recordDate=yyyy-MM-dd` | C 端登录 | 按日查询当前用户的饮食记录（含明细与照片 URL） |
| `DELETE /api/v1/meal-records/{id}` | C 端登录 | 逻辑删除饮食记录（含明细），仅限当前用户 |

保存请求体字段：`recordDate`（必填）、`nodeCode`（必填，餐点）、`mediaId`（餐食照片 ID，可空，须为当前用户 `MEAL` 类型）、`foodsText`（可空，≤512，为空时按明细食物名拼接兜底）、`carbsG`（可空，≥0，为空时按明细求和兜底）、`hungerLevel`（可空，`1` 饱腹 / `2` 一般 / `3` 微饿）、`supplementText`（可空，≤512）、`predictNow` / `predictSuggest`（可空，`(0,40)`）、`suggestText`（可空，≤512）、`items`（可空，≤50，每项含 `foodId` 可空、`foodName` 必填 ≤64、`weightG` / `carbsG` / `caloriesKcal` 可空 ≥0，`foodId` 非空时须为上架食物）。

预测三列 `predictNow` / `predictSuggest` / `suggestText` 由 prediction 客户端串联在用户确认后携带，服务端只校验范围与长度、不重算；两个模块未上线期间传空即可。删除后同日同餐点可重新保存。

## 餐后血糖预测

用户端通过 `POST /api/v1/predictions/meal-glucose` 获取餐后 1 小时血糖预测。服务端为纯计算：不落库、不查 `glucose_record` / `meal_record`，自身无持久化，可重复调用（重算 = 同接口携带补充信息重复调用）；唯一服务端读取是健康档案的分类参数（经 `profile.health` 服务接口自取，未建档时兜底建档 + 默认参数分档，正常返回）。食物数据由客户端从 ai 分析结果转发（ai 未上线时前端可手输联调），当前血糖由客户端传入，服务端不查血糖记录、不设默认值。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/predictions/meal-glucose` | C 端登录 | 餐后 1h 血糖两段式预测（现状 / 建议文案 / 按建议量再预测），`nodeCode` 须为餐点节点（`breakfast` / `lunch` / `dinner`） |

请求体字段：`nodeCode`（必填，餐点）、`currentGlucose`（必填，`(0,40)` mmol/L）、`carbsG`（必填，≥0）、`items`（可空，≤50，每项 `foodName` 必填 ≤64、`categoryCode` 必填 `1-9` 且**透传不落库**——前端从 food 库选食时详情自然带 `category_code` 填入、`weightG` / `carbsG` 可空 ≥0）、`portionRatio`（可空，`(0,2]`，默认 1.0）、`medicationTaken`（可空布尔，默认未服药）、`exerciseDone`（可空布尔，默认未运动）、补充原文（可空，≤200）。

响应 `data`：`predictNow`（现状预测）、`suggestText`（建议文案——`items` 非空走分食物建议，为空退回碳水换算，均不调 LLM）、`predictSuggest`（按建议量再预测）、`algorithmVersion`（`"v1"`，算法数值/系数/文案变更时升级）。预测数值均收口到 `[0.1, 39.9]` 并保留 1 位小数。

| 场景 | code |
| --- | --- |
| `nodeCode` 缺失/空白、`currentGlucose` 缺失或 ∉ `(0,40)`、`carbsG` 缺失或为负、补充标签非法值、`items` 要素非法（`foodName` 缺失、`categoryCode` 缺失或 ∉ `1-9`、数值越界） | `40000` BAD_REQUEST |
| `nodeCode` ∉ 餐点节点 | `40001` BIZ_REJECTED |
| 未建档（首次调用触发兜底建档 + 默认参数，正常预测） | `0` |
| 未登录 / 令牌失效 | `40100` UNAUTHORIZED |
| 系统异常 | `50000` INTERNAL_ERROR |

出参示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "predictNow": 8.4,
    "suggestText": "米饭 150g · 红烧肉正常 · 青菜正常",
    "predictSuggest": 7.8,
    "algorithmVersion": "v1"
  },
  "requestId": "d7b3..."
}
```

## 对话与图片消息

用户端对话页（`frontend/pages/voice/voice`）的文本与图片消息走同一组接口：**图片不单独开接口**，只换 `msgType` 与媒体引用字段。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/chat-sessions/current` | C 端登录 | 获取或创建当前会话（2.23） |
| `POST /api/v1/chat-sessions/{sessionId}/messages` | C 端登录 | 写入用户消息（2.24）；`clientMsgId` 必填且幂等 |
| `POST /api/v1/chat-sessions/{sessionId}/completions` | C 端登录 | SSE 流式补全（2.25） |

`msgType` 语义：`1` 文本 / `2` 图片 / `3` 语音。

- `msgType=1`：必填 `contentText`；
- `msgType=2`：必填 `mediaId`；`contentText` 可同时携带用户随图片一起发的文字，也允许为空（纯图片）；
- `msgType=3`：必填 `voiceMediaId`（ASR 未接入，服务端暂不生成回复）。

**媒体引用必须属于当前用户。** 服务端对 `mediaId` / `voiceMediaId` 执行「主键 + `userId` + 业务类型 + 可用状态」的归属校验，业务类型分别为 `CHAT_IMAGE(4)`、`CHAT_VOICE(6)`；2.25 在读图之前会再校验一次。不属于当前用户的引用一律拒绝，且错误文案**不区分「不存在」与「不是你的」**，避免泄露他人 `mediaId` 是否存在。

**图片消息的上传顺序（三步不可颠倒）：**

1. `POST /api/v1/media-files/upload-credentials`（`bizType=4`）→ 得到 `storageKey` 与预签名 PUT URL；
2. 客户端直接把文件字节 PUT 到该 URL（有效期 300 秒，不经过后端）；
3. `POST /api/v1/media-files` 登记 → 得到 `mediaId`，此后才能用于 2.24。

**2.25 的 SSE 事件契约不变**：仍是 `delta` / `done` / `error`，`done` 必带完整正文。图片轮次对客户端**完全透明**——服务端内部先调用视觉模型产出客观描述，再把描述交给文本模型组织回答，2.25 的入参出参与文本轮次一模一样，前端不需要区分也不需要新增事件。

客户端注意两点：

- 图片轮次首字延迟明显高于文本轮次（要先完成一次视觉识别），沿用现有「正在思考…」占位即可，**不要用固定超时判定失败**；
- 服务端会把图片的描述写入该条消息，供后续轮次回看（例如发完图再问「那我晚饭吃多少」），因此多轮追问图片内容是支持的。
