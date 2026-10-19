# API 契约

本文件是 `xiaoyejinjier/GlucoPilot-backend` 的权威契约。本文历史路径中的 `frontend/` 指独立仓库 `GlucoPilot-frontend` 根目录，`admin/` 指 `GlucoPilot-admin` 根目录，`backend/` 指本仓库根目录；拆仓不改变接口路径或响应格式。

## 设备添加准备（2026-10-15，尚未接入真实设备）

用户确认尚无具体设备型号。本次仅增加小程序页面、微信原生BLE发现、扫码/SN临时登记，不增加HTTP接口或后端DTO。登记数据仅保留在本次登录会话内存，统一标记“待适配”，不等于验证、绑定或连接成功；不写入glucose_record，不产生模拟血糖。

后续须确认厂商型号、二维码/SN规则、BLE服务/特征UUID、授权配对、读数解码与单位/时间约定，再由接口负责人确认绑定和读数入库契约。不得把任意附近蓝牙设备当成已支持血糖仪，不得用名称或SN猜测读数；二维码只提取SN文本，绝不自动请求或打开其链接。

## 高低血糖异常事件中心（2026-10-15）

### 给用户的回复

内部 `note` 仍只在管理端可见；新增 `userReply`（去空白后最多1000字），独立持久化到case与log（迁移 `V17__glucose_alert_user_reply.sql`），case另存 `repliedAt`。处理请求中省略/null保留回复，空字符串撤回，非空发布；历史内部备注不自动公开。

管理端列表/详情返回 `userReply/repliedAt`，日志返回当次操作后的 `userReply`。回复改变才更新 `repliedAt`（撤回时置空）；仅改内部备注不改变回复时间。幂等及版本校验包含回复。已处理/已关闭事件可在状态和内部备注不变时补改公开回复，不得重开，追加审计日志。

`GET /api/v1/glucose-alerts/replies?pageNum=1&pageSize=3`：C端登录，只查询当前用户的非空回复，按回复时间及case ID倒序，使用统一PageResult。调用方不得指定userId。白名单字段为 `{ id, recordId, recordDate, nodeCode, nodeName, glucoseValue, recordedAt, userReply, repliedAt }`，id为case ID；不返回内部备注、日志、操作人、手机号或他人数据。原记录/账号删除后不公开；原测量更正后，旧回复保留并明确关联旧快照，不附着到新测量。

用户端仅提供紧凑消息入口，由用户点击打开提醒/回复弹窗，支持刷新、分页和重试。退出页面、账号变化和晚到请求不得泄漏旧账号回复；原录入、拍照、曲线和统计不变，无用户写操作或强制弹窗。

### 异常事件查询及处理

- 规则版本 `glucose-alert-v1`：有效实测 `(0,40)` 中 `<3.9` 为LOW，`>=10.0` 为HIGH，单位mmol/L。这是产品提醒阈值，不替代个人诊疗目标；缺失值和餐食预测不生成提醒，不改变原图表/达标率。
- 来源为未删除的 `glucose_record` 六个非餐点节点，排除删除账号。当前事件id为血糖记录ID字符串，默认PENDING；运营跟进保存到 `gp_glucose_alert_case`，追加审计到 `gp_glucose_alert_log`（迁移V16），不改历史血糖。
- `glucoseValue + recordedAt` 构成测量快照；重新测量或改值后新快照待处理，旧跟进不得覆盖新测量。原记录恢复正常或删除后退出当前异常列表，日志保留。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /admin/v1/glucose-alerts` | `health:alert:view` | 参数 `startDate/endDate`（默认近30天，最多92天）、`keyword`（用户名/昵称，最多64字）、`alertType`（LOW/HIGH）、`status`、`pageNum/pageSize`；返回 `{ page, overview, lowThreshold, highThreshold }`，overview统计整个筛选范围而非当前页 |
| `GET /admin/v1/glucose-alerts/{id}` | `health:alert:view` | 返回 `{ event, history }`，包括当前真实血糖、原备注、脱敏手机号和全部快照处理日志 |
| `PUT /admin/v1/glucose-alerts/{id}/handling` | `health:alert:handle` | 请求 `{ glucoseValue, recordedAt, expectedVersion, status, note, userReply? }`；快照必填、版本从0开始、note去空白后1～1000字，返回当前事件 |

状态：`PENDING → PROCESSING/RESOLVED/CLOSED`，`PROCESSING → PROCESSING/RESOLVED/CLOSED`；终态不得重开。RESOLVED仅表示运营跟进完成，不表示血糖恢复。记录操作人、时间、状态、快照与requestId；行锁及expectedVersion防止覆盖。相同操作人重复提交相同状态/备注/回复幂等返回，不增日志；过期快照/版本冲突返回40900提示刷新。PUT按状态和版本保证幂等，不依赖随机客户端key。

查看权限继承已有健康档案查看角色，处理权限默认管理员；运维可另行分配。所有响应继续使用 `ApiResponse { code, message, data, requestId }`、成功码0、保留X-Request-Id，鉴权使用现有机制。不提供改删原血糖或用药剂量功能。

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

## 管理端九时段记录可视化

管理端通过 `GET /admin/v1/health-records/nine-slot` 查看 C 端用户真实入库的九时段数据，权限复用 `health:archive:view`。查询参数为 `startDate`、`endDate`、`keyword`、`pageNum`、`pageSize`；日期为空时默认最近 30 天（含当天），单次查询最多 92 天，`keyword` 匹配用户名、昵称或手机号。

接口按“用户 + 业务日期”分页，返回 `overview` 与 `page`：

- `overview` 包含 `activeUsers`、`recordDays`、`glucoseRecords`、`mealRecords`、`totalRecords`、`avgGlucose`、`complianceRate`、`abnormalRecords`、`trend` 和 `nodeStats`；
- `page` 使用统一 `PageResult`，每条记录包含用户、日期、完成数、实测血糖均值以及 `slots`；`slots` 固定覆盖九个节点；
- 六个非餐点节点读取 `glucose_record`，返回实测 `glucoseValue`、来源、备注和记录时间；
- 早餐、中餐、晚餐读取 `meal_record`，返回食物摘要、碳水、预测值和记录时间；正餐预测值只能作为饮食记录详情展示，禁止计入 `avgGlucose`、达标率或真实血糖趋势；
- 达标口径与现有用户管理一致：实测血糖 `[3.9, 7.8)` 为达标，其他实测值计入异常记录。

接口只读，不提供后台修改或删除用户健康记录的能力。响应继续使用统一 `ApiResponse { code, message, data, requestId }`，成功码为 `0`。

## 管理端业务列表分页

用户、食品和反馈三个管理列表统一使用服务端分页，响应 `data` 均为 `PageResult { pageNum, pageSize, total, pages, records }`。`pageNum` 从 1 开始，`pageSize` 范围为 1～100；筛选条件由服务端执行，管理端不得先拉取固定数量的数据再进行本地分页。

| 方法与路径 | 可选筛选参数 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET /admin/v1/users` | `keyword`、`accountStatus`、`pageNum`、`pageSize` | `user:manage` | C 端用户分页；关键词匹配用户名、昵称或手机号 |
| `GET /admin/v1/foods` | `keyword`、`categoryId`、`enabled`、`pageNum`、`pageSize` | `food:library:manage` | 食品分页；`enabled=true/false` 对应上架/下架 |
| `GET /admin/v1/feedbacks` | `keyword`、`feedbackType`、`replyStatus`、`pageNum`、`pageSize` | `feedback:manage` | 反馈分页；类型为 `1～4`，处理状态为 `0～3` |

运营端反馈列表按反馈 `id` 从小到大排序后执行分页，先提交的记录排在前面。页面只显示一列连续“序号”，计算方式为 `(pageNum - 1) * pageSize + 行索引 + 1`，翻页后继续编号；数据库 `id` 保留在接口中用于审批/回复，不另外显示为列表编号。

管理端食品营养库按表格实际可用高度及行高自动计算每页条数（范围1～100），不固定22条；窗口/容器尺寸变化后重算容量并回到第一页，相同容量不重复请求。分页栏保留在表格下方，支持切换页码或跳页。每次翻页均调用 `/admin/v1/foods` 获取服务端分页结果，禁止对旧数组响应进行本地切片以冒充服务端分页。关键词、分类与上下架筛选提交后回到第一页，后续翻页保留已提交的筛选条件；新增食品回到第一页，删除或下架导致当前页超出总页数时自动回到最后一个有效页。

反馈管理、用户管理和九时段用户每日明细使用同样的分页交互：默认 10 条、可选 10/20/50/100 条，表格内部滚动，分页栏展示总数、当前页和记录区间。页码与每页条数改变时重新请求各自服务端接口，筛选条件只在提交查询时生效并随翻页保留；反馈状态、用户状态变更或数据减少导致当前页越界时自动回退。九时段按“用户 + 日期”分页，`overview` 统计整个查询范围，不按当前页记录数计算，也不得随翻页丢失已提交的日期范围。

## 管理端食物图片

新增与编辑食物可选择内置图片或已上传的食物图片，也可上传新的 JPG/PNG 图片（单张 1 字节至 5MB）。上传采用现有对象存储的“凭证 → PUT 文件字节 → 登记”流程，不将 base64 或浏览器临时地址写入食品表。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /admin/v1/food-images` | `food:library:manage` | 查询已登记、可用的食物图片，返回 `{ id, fileUrl }[]` |
| `POST /admin/v1/food-images/upload-credentials` | `food:library:manage` | 请求 `{ bizType: 5, mimeType, sizeBytes }`；返回 `{ uploadUrl, storageKey, expireIn }` |
| `POST /admin/v1/food-images` | `food:library:manage` | 请求 `{ bizType: 5, storageKey, mimeType, sizeBytes }`；返回 `{ id, fileUrl }`；携带 `X-Idempotency-Key`，相同对象重复登记复用原记录 |

食物图片使用 `media_file` 的 `CMS(5)` 类型、`biz_id=FOOD_IMAGE` 和当前运营 `ops_id`，`user_id` 为空。服务端验证对象所属运营账号、文件大小、真实图片格式与尺寸，禁止登记其他用途图片。图片稳定地址为 `/api/v1/foods/images/{id}`；该地址公开读取经过登记的食物配图，成功返回图片二进制（与已有文件读取接口一致），失败返回统一 `ApiResponse`。私有对象存储通过服务端读取，不将短期签名下载地址保存到 `food_nutrition.image_url`。小程序从统一地址配置解析此相对路径。

管理端食品大类也可指定图片：`GET /admin/v1/food-categories` 返回 `{ id, name, sort, image }`；分类新增与编辑请求支持 `{ name, sort, image }`，`image` 为可选字段、最多 512 字符。图片地址持久化到 `food_category.image_url`（新增迁移 `V14__add_food_category_image.sql`）；旧分类没有图片时，由管理端根据分类名称展示内置图片。旧调用方省略 `image` 时保留原分类图片；传空字符串时清空自选图片并使用默认图。选择已有图片和上传新的 JPG/PNG 图片复用上述食物图片接口，分类列表与分类选择下拉框展示保存后的图片。

分类保存成功响应必须使用数据库回读结果；管理端保存后再次读取分类列表，确认目标分类的 `image` 与提交值一致才提示成功。后端仍返回旧结构、漏存图片或列表读取失败时，保留编辑弹窗与已上传图片并显示具体错误，禁止提示保存成功后悄悄退回默认图。

九时段用户每日明细默认 10 条全部展开，分页栏置于这 10 条下方；每页选择更多条数时才限制表格高度并启用内部滚动。

## 免责声明版本门禁

用户登录或小程序从后台重新进入前台时，客户端读取 `/api/v1/users/me` 中的 `settings.disclaimerAgreed`、`settings.disclaimerVersion`，并与公开配置 `/api/v1/app-config?groups=TEXT` 返回的 `text.disclaimer.version` 比较。未同意，或已同意版本与当前版本不一致时，必须通过 `reLaunch` 进入免责声明页，阅读到底并成功提交同意前不得进入业务页面；已同意当前版本的用户正常进入首页，不重复打扰。

更新免责声明时，运维必须同时更新 `text.disclaimer` 与 `text.disclaimer.version`。用户通过 `POST /api/v1/users/me/settings/disclaimer-agreement` 提交当前版本，服务端再次校验版本并记录 `disclaimer_agreed`、`disclaimer_version`、`disclaimer_agreed_at`；版本不一致时拒绝提交并要求重新读取最新文案。

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

## 九时段统计

2026-10-14 记录页今日曲线交互：固定保留九时段横轴位置，仅非餐点的有效实测血糖参与折线和Y轴范围。点击实心点高亮该点，并在曲线下方展示时段、血糖、真实记录时间、来源和备注；点击空心占位点只提示无实测数据，空白处取消选择，不修改记录、不受录入截止限制。使用Canvas本地坐标进行扩大命中判断，滚动/多指操作不当作点击；不使用遮盖Canvas的弹窗。

沿用 `GET /api/v1/day-boards/{recordDate}` 与 `GET /api/v1/meal-records` 同步当日看板，同时调用既有 `GET /api/v1/glucose-records?startDate=yyyy-MM-dd&endDate=yyyy-MM-dd&pageSize=100` 获取真实 `recordedAt/sourceType/remark`。血糖记录列表成功时优先使用其完整快照；列表失败时可使用看板值，但只在缓存血糖值一致时保留旧记录时间，否则显示“时间未提供”，不得用当前时刻补齐。旧同步/绘制回调在离开页面或切换日期后须失效；无服务端DTO变更。

2026-10-14 日历视图层级调整：`month` 模式显示所选年份的1～12月入口，点击月份切换至 `day` 模式。`day` 模式展示该月完整日期日历，原完成度着色及选中日期高亮保留；日历下方同时显示选中日期的九时段明细、完成率及实测均糖，不再以日明细替换日期日历。支持切换年份、月份、相邻日期及返回月份列表。月份入口只表示导航，不伪造全年统计、不额外添加年度接口；日期日历沿用统计接口，日明细并行调用血糖记录与饮食记录接口，加载/错误状态独立，快速切换时不得显示旧日期数据。选择月份或日期仍清除手动选周覆盖，血糖周视图默认跟随该日期。

用户端时段选择与拍照、确认保存共用截止规则（设备当地时间）：10:00 截止早餐前与早餐；14:00 截止早餐后、午餐前与午餐；20:00 截止午餐后、晚餐前与晚餐；晚餐后不设日内截止。截止时间点本身视为已截止，页面回到前台、点击节点和确认保存时均再次校验。该规则限制当天录入入口，不影响已保存记录的查询与后台展示。

用户端通过 `GET /api/v1/glucose-records/summary` 获取统计页数据。每天的完成度按九时段计算：六个非餐点节点读取 `glucose_record`，早餐、中餐、晚餐三个餐点节点读取 `meal_record`；同一日期同一节点最多计一次，因此单日完成数范围为 `0-9`。正餐是否由拍照识别生成不影响统计，只要该餐点已有有效饮食记录即视为完成。

统计页周曲线必须传入当前选中日期所在自然周的 `startDate` 与 `endDate`（周一至周日），不能只传 `year/month` 后始终展示服务器当前周。曲线按每日实测血糖均值绘制；正餐记录不作为血糖值，无记录日期保留横轴占位，已有数值点按日期顺序连接。

2026-10-14 血糖视图支持独立选周：默认跟随日历/月视图选中日期；点击曲线卡片中的周日期范围弹出选周面板，按月份列出与该月相交的完整自然周（允许跨月、跨年），可切换月份并选择。手动选周仅替换请求中的 `startDate/endDate`，`year/month` 仍对应原日历月份，不改变日历选中日期；月度指标与时段分布仍使用原日历月份。后续改变日历日期/月份或点击“跟随日历”时，清除手动周覆盖。周曲线和周摘要同步更新；快速切周时旧请求不得覆盖新选择，失败须显示重试状态而非把旧数据当成新周结果。沿用现有接口，无后端 DTO 变更。

响应中的 `calendar.days[].recordCount`、`monthStats.totalRecords`、`monthStats.recordDays`、`checkIn.todayCount`、`checkIn.streakDays` 和 `checkIn.monthRecordDays` 均按血糖与正餐记录合并后的九时段口径计算；`checkIn.totalNodes` 固定来自启用的九时段字典。`monthStats.avgValue/maxValue/minValue`、`week`、`slotStats` 和 `trend` 只统计具有真实数值的血糖记录，禁止把餐食预测值当成实测血糖。

`monthStats.complianceRate` 当前表示当月有任一时段记录的天数除以当月总天数，客户端必须展示为“记录率”或“打卡率”，不得展示为血糖达标率。

## 饮食记录

### 正餐照片识别与营养库匹配

确认照片后上传为 `MEAL(3)`，调用 `POST /api/v1/meal-analysis`，请求 `{ mediaId, nodeCode, foodText? }`。需要用户登录，校验照片归属和餐点节点，补充文字最多200字；携带稳定 `X-Idempotency-Key`，客户端超时90秒。取消/离开页面不写入饮食记录。

餐食场景复用千问视觉凭据，独立使用 `ai.meal.model`（`AI_MEAL_MODEL`，默认 `qwen3-vl-plus`），不改变血糖 OCR 模型。响应含 `model`、`items`、`carbsG`、`caloriesKcal`、`matchedCount`、`newFoodsCount`、`warnings`。明细含食物名、`foodId`（仅上架食品）、`libraryFoodId`、分类码、估算重量、每100g营养、按份量计算的营养、GI（库中已有时）、`nutritionSource`、`libraryStatus`、置信度。无法确定的数值返回空，客户端要求补充或重新识别，不得替换为示例值。

规范化食品名后精确匹配 `food_nutrition`，已有食品必须使用库中营养，严禁模型覆盖。可信的新食品自动入库为待审核条目（`shelf_status=0`、`nutrition_source=AI_ESTIMATE`），保留来源模型；使用现有唯一键防止重复登记。模型估算明确标记，运营核对营养后可编辑上架。待审核 ID 不作为上架 `foodId` 提交，饮食记录保存食物名和营养快照。迁移 `V15__add_food_nutrition_source.sql` 保存来源。

确认食物及份量后使用原饮食记录接口保存照片与明细。真实血糖预测必须提供真实当前血糖并调用现有 `/api/v1/predictions/meal-glucose`，未提供时不显示数值预测，仍可保存饮食。

2026-10-14 用户授权的临时演示：餐食结果页暂不调用真实预测接口，识别成功后自动展示“模拟预测评分”。前端适配入口为 `frontend/services/meal-score-preview.js` 的异步 `getMealScorePreview(input)`，入参包含餐点、食物明细、营养合计、可空实测血糖、饱腹感；返回 `{ schemaVersion: 1, source: 'MOCK', score: 85, maxScore: 100, label, description }`。85分是固定展示样例，不根据营养或血糖计算，不代表真实评估；食品识别、食品库匹配及营养数据仍为真实接口结果。修改补充信息、重新识别、识别失败或退出页面时清除失效评分。

模拟评分只保存在当前页面，不提交 `score`、`predictNow`、`predictSuggest` 或模拟建议，不写入本地饮食缓存或数据库、不参与统计。后续由预测模块负责人确认真实评分接口契约，再替换上述适配入口；现有后端预测实现和请求/响应字段不改动。

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
| `GET /api/v1/chat-sessions/{sessionId}/messages` | C 端登录 | 按游标读取当前用户的历史消息 |
| `POST /api/v1/chat-sessions/{sessionId}/messages` | C 端登录 | 写入用户消息（2.24）；`clientMsgId` 必填且幂等 |
| `POST /api/v1/chat-sessions/{sessionId}/completions` | C 端登录 | SSE 流式补全（2.25） |

`msgType` 语义：`1` 文本 / `2` 图片 / `3` 语音。

历史查询参数：`pageSize` 默认 20，范围 1–100；`beforeSeq` 可选正整数，表示仅取该序号之前的消息。首次不传游标，读取最新一页。返回 `data={records,hasMore,nextBeforeSeq}`，每页 `records` 按 `seqNo` 升序，向前翻页使用 `nextBeforeSeq`，以 `hasMore` 判断是否需要继续。仅返回当前用户拥有的会话中未删除的用户、助手消息，不返回系统提示词；会话不存在或不属于当前用户统一返回 404。记录字段包括 `id,seqNo,msgRole,msgType,contentText,mediaId,voiceMediaId,mediaUrl,modelCode,createdAt,finishReason`，Long ID 仍为字符串。`mediaUrl` 仅对仍可用且归属匹配的图片返回，媒体不可用时为 null，文本历史仍保留。重新进入当前会话时先恢复历史，再在末尾显示本次完整欢迎卡片；欢迎卡片仅用于展示，不写库、不触发模型调用。已结束或归档会话只读，历史可继续分页。

会话列表使用 `GET /api/v1/chat-sessions`，删除使用 `DELETE /api/v1/chat-sessions/{sessionId}`，置顶使用 `PATCH /api/v1/chat-sessions/{sessionId}/pinned`（`isPinned`），新对话复用 `/current` 并传 `forceNew=true`。这些接口与 V21 会话生命周期更新保持一致。

- `msgType=1`：必填 `contentText`；
- `msgType=2`：必填 `mediaId`；`contentText` 可同时携带用户随图片一起发的文字，也允许为空（纯图片）；
- `msgType=3`：必填 `voiceMediaId`（ASR 未接入，服务端暂不生成回复）。

**媒体引用必须属于当前用户。** 服务端对 `mediaId` / `voiceMediaId` 执行「主键 + `userId` + 业务类型 + 可用状态」的归属校验，业务类型分别为 `CHAT_IMAGE(4)`、`CHAT_VOICE(6)`；2.25 在读图之前会再校验一次。不属于当前用户的引用一律拒绝，且错误文案**不区分「不存在」与「不是你的」**，避免泄露他人 `mediaId` 是否存在。

**图片消息的上传顺序（三步不可颠倒）：**

1. `POST /api/v1/media-files/upload-credentials`（`bizType=4`）→ 得到 `storageKey` 与预签名 PUT URL；
2. 客户端直接把文件字节 PUT 到该 URL（有效期 300 秒，不经过后端）；
3. `POST /api/v1/media-files` 登记 → 得到 `mediaId`，此后才能用于 2.24。

开发环境默认使用后端自带的本地文件存储，不要求开发者另外安装 MinIO。此时第 1 步返回的是同一后端生成的一次性 PUT URL（`/api/v1/media-files/local-uploads/{token}`），该 URL 与本地文件读取地址仅在 `storage.provider=local` 时开放；向该本地上传地址 PUT 文件字节时必须携带 `Content-Type: application/octet-stream`，禁止使用 `application/json`，否则 JSON 请求过滤器会破坏二进制内容。启用 OSS 的生产环境仍使用私有 OSS 预签名 URL，并遵循凭证签名约束，不额外添加参与签名的请求头。除这一传输头差异外，调用方始终按上述三步协议处理。 课程部署可显式关闭对象存储，按下方 disabled 契约拒绝上传；本次功能不强制启用云存储，也不改变现有关闭模式。

**2.25 的 SSE 事件契约不变**：仍是 `delta` / `done` / `error`，`done` 必带完整正文。图片轮次对客户端**完全透明**——服务端内部先调用视觉模型产出客观描述，再把描述交给文本模型组织回答，2.25 的入参出参与文本轮次一模一样，前端不需要区分也不需要新增事件。

客户端注意两点：

- 图片轮次首字延迟明显高于文本轮次（要先完成一次视觉识别），沿用现有「正在思考…」占位即可，**不要用固定超时判定失败**；
- 服务端会把图片的描述写入该条消息，供后续轮次回看（例如发完图再问「那我晚饭吃多少」），因此多轮追问图片内容是支持的。


## 部署时关闭文本 AI

服务器设置 `SPRING_AI_MODEL_CHAT=none`、`AI_VISION_ENABLED=false` 时，不初始化模型客户端，不需要 DeepSeek Key。
非 AI 接口继续工作；实际请求文本 AI 时返回现有 ApiResponse 业务拒绝错误（BIZ_REJECTED），提示“文本 AI 未启用”。
接口路径、成功响应和 SSE 协议保持现有契约；启用时恢复 `SPRING_AI_MODEL_CHAT=deepseek` 并设置真实 Key。


## 课程部署：关闭对象存储

设置服务器环境变量 `STORAGE_PROVIDER=disabled` 时，不创建 OSS 或 MinIO 客户端，不需要云存储凭据。
媒体上传凭证、对象校验和读取等存储操作返回现有 ApiResponse 的 BIZ_REJECTED，提示“文件存储未启用，当前环境不支持文件上传与读取”。
不生成虚假上传 URL、不登记不存在的媒体、不返回模拟上传成功。
已有接口路径、DTO、响应封装保持不变；其他模块仍可使用数据库和 Redis。
此模式不覆盖上传功能验收，头像上传、饮食图片上传、图片聊天等依赖对象存储的操作不可用。
## 血糖仪 OCR 识别

血糖仪图片先按媒体三步流程上传并登记为 `GLUCOSE(2)`，随后创建 OCR 任务。OCR 只识别图片中的血糖数值和单位；餐前/餐后节点由用户进入拍照页前选择，服务端不得根据图片猜测 `nodeCode`。识别结果只用于预填，必须由用户确认或修改后再调用血糖记录接口入库。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/ocr-tasks` | C 端登录 | 创建异步识别任务；请求体 `{ "mediaId": 1001, "bizType": 1 }`，须携带稳定的 `X-Idempotency-Key` |
| `GET /api/v1/ocr-tasks/{id}` | C 端登录 | 查询当前用户的任务状态与识别结果 |

任务状态统一为：`0` 待处理、`1` 处理中、`2` 成功、`3` 失败。`bizType=1` 表示血糖识别；本期不开放 `bizType=2` 餐食 OCR。媒体必须属于当前用户、状态可用且业务类型为 `GLUCOSE(2)`。

成功结果中的 `ocrResult` 固定为：`schemaVersion=1`、`bizType=1`、`suggestedValue`（`mmol/L`，无法可靠识别时为 `null`）、`confidence`（`0-100`）和 `rawText`。即使状态成功，`suggestedValue=null` 也表示图片质量不足，客户端必须进入手工输入。任务失败时返回 `errorMsg`，客户端仍应允许用户手工记录。

用户确认后调用 `PUT /api/v1/glucose-records/by-node`：采纳 OCR 值时必须传 `sourceType=2` 和原 `mediaId`；用户改为手工输入时传 `sourceType=1`，但仍可携带原 `mediaId` 供追溯。非空 `mediaId` 必须属于当前用户、状态可用且类型为 `GLUCOSE(2)`。血糖值继续执行 `(0,40)` 的服务端范围校验，识别接口不得直接写入 `glucose_record`。

## 邮箱登录（合入）

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| POST /api/v1/auth/email/codes | 匿名 | 向邮箱发送 6 位验证码；scene 为 login 或 ind；同邮箱 60 秒冷却 |
| POST /api/v1/auth/email/sessions | 匿名 | 邮箱验证码登录 |
| PATCH /api/v1/users/me/email | C 端登录 | 绑定/换绑邮箱 |

## 设备数据同步

用户端通过 POST /api/v1/device/glucose-sync 提交厂商原始报文。请求体为 { provider, deviceSn?, payload }：provider 路由到对应适配器（MOCK / HUAWEI / HEALTH_CONNECT / APPLE_HEALTH / NIGHTSCOUT），适配器将 payload 转为统一血糖样本后按用户、业务日期和血糖节点幂等写入。MOCK 的 payload 为 { readings:[{ recordDate, nodeCode, value, unit, externalRecordId }] }。

## 管理端设备档案

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| GET /admin/v1/devices | device:manage | 分页查询 |
| POST /admin/v1/devices | device:manage | 创建 |
| PUT /admin/v1/devices/{id} | device:manage | 更新 |
| PUT /admin/v1/devices/{id}/status | device:manage | 启用/禁用 |
| DELETE /admin/v1/devices/{id} | device:manage | 逻辑删除 |

