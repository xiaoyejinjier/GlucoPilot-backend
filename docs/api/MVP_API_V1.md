# GlucoPilot MVP API V1（对齐版）

> **状态**：草案 v2 —— 已对齐《数据库设计文档》（20260914-01-02，38 表）、生产库 DDL（`backend/sql/intelligent_health_assistant.sql` v2.1，38 表）与《接口设计文档》（92 个接口）。
> **定位**：本文**不再定义任何新接口**，只声明 MVP（一期）的实现范围、依赖库表、联调顺序与验收口径。**接口契约一律以 `接口设计文档.docx` 为准**（编号 2.x 为 C 端、3.x 为后台、4.x 为推送与 AI 配置、5.x 为 C 端补充）。
> **编写日期**：2026-09-15 ｜ 替换此前的 MVP 草案（旧稿与库表/接口文档存在冲突，见第 1 章修正记录）。

---

## 1. 与旧版 MVP 草案的修正记录

| # | 旧稿内容 | 库表 / 接口文档的实际情况 | 本次修正 |
|---|---|---|---|
| 1 | 认证：手机号 + 密码 | `user_account` 无 `password_hash` 列（仅 `ops_account` 有）；接口文档为微信登录 + 短信验证码 + 手机号登录 | **改为** 微信登录（2.1）/ 短信验证码（2.2）/ 手机号登录（2.3）；不再有"密码登录" |
| 2 | 表名 `app_user`、`health_profile`、`meal_food`、`file` | 实际为 `user_account`、`user_health`、`meal_food_item`、`media_file` | 全部**改用真实表名** |
| 3 | `ai_task` 表 + `POST /ai/food-recognitions` | 库表与接口文档均无 `ai_task` / `food-recognitions`；识别能力由 `ocr_task` 承载 | **改为** OCR 任务接口（2.21 创建 / 2.22 查询，`bizType`：1 血糖、2 餐食） |
| 4 | `prediction_record`、`meal_response` 表 + `POST /predictions` | 库表与接口文档均无这两张表与预测接口 | **改为** 预估能力由 `meal_record.predict_now / predict_suggest / suggest_text` 字段承载，通过**饮食分析接口（2.20）**写回 |
| 5 | 血糖/饮食用 `measureTime` + `mealRelation` 时间模型 | 实际为**九时段节点模型**：`(record_date, node_code)`，唯一键 `uk_user_date_node` | **改为** `PUT /glucose-records/by-node`（2.17）、`PUT /meal-records/by-node`（2.19） |
| 6 | 统计：`/statistics/overview` + `/statistics/glucose-trend` | 接口文档为**血糖统计摘要** `GET /glucose-records/summary`（含日历 level、月统计、周曲线、分时段均值） | **改为** 2.18 血糖统计摘要 |
| 7 | 声明"血糖图片 OCR 不属于产品需求" | 库表已有 `ocr_task`，接口文档有 OCR 两接口（`bizType=1 血糖`） | 一期**可选纳入**（血糖仪读数 + 餐盘识别共用一套任务） |

---

## 2. MVP 目标与核心闭环

**目标**：跑通"C 端记录 → 服务端分析 → 数据沉淀 → 可查看/可导出"的最小闭环，并把 AI 能力（OCR 识别 + 饮食预估）以**用户确认后入库**的方式接入。

```
① 登录               2.1 微信登录 / 2.2 短信验证码 / 2.3 手机号登录
② 完善资料与档案      2.7 更新用户资料 / 2.8 保存健康档案 / 2.9 偏好设置 / 2.10 同意免责声明
③ 取九时段字典        2.12 GET /dict/glucose-nodes（is_meal 区分餐点/非餐点）
④ 看当日看板          2.16 GET /day-boards/{recordDate}（9 节点血糖+饮食聚合，前端禁止本地混存）
⑤ 录餐前血糖          2.17 PUT /glucose-records/by-node（nodeCode 必须为非餐点）
⑥ 上传图片            2.14 POST /media-files/upload-credentials → 直传 → 2.15 POST /media-files 登记
⑦ AI 识别             2.21 POST /ocr-tasks（bizType：1 血糖 / 2 餐食）→ 2.22 GET /ocr-tasks/{id} 轮询
⑧ 用户确认后录饮食     2.19 PUT /meal-records/by-node（foodItems 明细可选）
⑨ 饮食分析（预估）     2.20 POST /meal-records/{id}/analysis（写回 predictNow / predictSuggest / suggestText）
⑩ 录餐后血糖          2.17 PUT /glucose-records/by-node（餐后节点）
⑪ 统计与档案          2.16 日看板 / 2.18 血糖统计摘要（日历 level、月统计、周曲线、分时段均值）
⑫ 反馈                2.31 POST /feedbacks → 2.32 GET /feedbacks
⑬ 数据出口            2.33/2.34 导出任务；2.35/2.36 数据备份与恢复
```

**红线**：AI 只产出候选 —— OCR 结果与预估血糖必须经用户确认/修改后才写入正式业务表；服务端不回传原始识别 JSON 全文。

---

## 3. MVP 范围

### 3.1 一期实现（In Scope）

| 分组 | 接口编号 | 说明 |
|---|---|---|
| 认证 | 2.1 / 2.2 / 2.3 / 2.4 / 2.5 | 微信登录、短信验证码、手机号登录、微信绑定、退出登录 |
| 用户与档案 | 2.6 / 2.7 / 2.8 / 2.9 / 2.10 / 2.11 / 2.13 | 当前用户信息、更新资料、健康档案、偏好设置、免责声明、绑定手机号、公开配置 |
| 字典 | 2.12 | 九时段节点字典（`isMeal`） |
| 媒体 | 2.14 / 2.15 | 上传凭证（预签名直传）、登记媒体元数据 |
| 血糖 | 2.16 / 2.17 / 2.18 | 日看板、按节点幂等写入、血糖统计摘要 |
| 饮食 | 2.19 / 2.20 | 按节点幂等写入、饮食分析（含预估写回） |
| AI 识别 | 2.21 / 2.22 | 创建/查询 OCR 任务 |
| 内容与工具 | 2.26 / 2.27 / 2.28 / 2.29 / 2.30 | 首页 Banner、资讯列表/详情、食物分类、食物营养（均只读） |
| 反馈 | 2.31 / 2.32 | 提交反馈、我的反馈列表 |
| 数据出口 | 2.33 / 2.34 / 2.35 / 2.36 | 创建导出任务、查询/下载、创建备份、恢复备份 |

### 3.2 一期不做（Out of Scope，但库表已就绪）

| 项 | 依据 |
|---|---|
| AI 聊天与流式补全（2.23~2.25） | `chat_session/chat_message/chat_context` 表已建，二期实现 |
| 跨会话长期记忆（4.14/4.15） | `chat_user_memory` 表已建，二期实现 |
| 消息推送（4.1~4.6） | `msg_push_*` 三表已建，二期实现 |
| 运营后台接口（3.1~3.34） | 库表与接口文档已定义，后台前端另有排期 |
| C 端补充接口（5.1~5.4 资讯互动、会话管理） | 二期实现 |
| 多图一餐、真实设备（CGM/血糖仪）直连 | **DDL 未预留设备来源列**：`glucose_record` 仅有 `source_type`（1 手动 2 OCR 3 设备），无 `provider_code` / `external_record_id`；一期仅手动/OCR/设备 Mock，设备直连需先走库表变更 |

---

## 4. MVP 关键字段与枚举（与接口文档一致）

### 4.1 血糖写入（2.17）

| 字段 | 类型 | 说明 |
|---|---|---|
| recordDate | Date | 业务日期 `yyyy-MM-dd` |
| nodeCode | String(32) | 非餐点节点（`dict_glucose_node.is_meal=0`） |
| glucoseValue | Decimal(4,1) | mmol/L，校验区间 `(0, 40)` |
| sourceType | Int | 1 手动、2 OCR、3 设备 |
| mediaId | Long | 照片 ID，可空（OCR 场景必填） |
| remark | String(256) | 备注，可空 |

### 4.2 饮食写入（2.19）

| 字段 | 类型 | 说明 |
|---|---|---|
| recordDate / nodeCode | Date / String(32) | 餐点节点：`breakfast` / `lunch` / `dinner` |
| mediaId | Long | 餐食照片 |
| foodsText | String(512) | 食物摘要 |
| carbsG | Decimal(8,1) | 碳水克 |
| hungerLevel | Int | 1 饱腹、2 一般、3 微饿 |
| supplementText | String(512) | 语音/文字补充 |
| foodItems | Array | 明细（`foodId`、`foodName`、`weightG`、`carbsG`、`caloriesKcal`） |

### 4.3 AI 与数据出口

| 场景 | 字段 | 约束 |
|---|---|---|
| OCR（2.21） | `mediaId`、`bizType` | `bizType`：1 血糖、2 餐食；结果由 2.22 轮询，**服务端转稳定 DTO，不透传原始 JSON** |
| 饮食分析（2.20） | 无业务入参（路径 `id`） | 同步写回 `predictNow` / `predictSuggest` / `suggestText`；建议超时 15s |
| 导出（2.33） | `exportScope`（1 近 7 天 / 2 近 30 天 / 3 自定义）、`startDate`、`endDate` | 落 `data_export_task.export_scope`；自定义必带起止日期；下载走短时效签名 URL |
| 备份（2.35） | 无业务入参 | `data_backup.payload_json` 必须含 `schemaVersion=1`；恢复（2.36）按 by-node 覆盖式 upsert |

### 4.4 通用约定（引用接口文档 §1）

- 统一响应：`{ code, message, data, requestId }`；`code=0` 成功；HTTP 状态码与 body 五位业务码同族（40000/40001/40100/40300/40400/40900/42901/50000）。
- 分页：请求 `pageNum` / `pageSize`（默认 20，最大 100）；响应含 `pageNum/pageSize/total/pages/records`。
- 幂等：`PUT …/by-node` 按 `(recordDate, nodeCode)` 覆盖写入；聊天消息用 `clientMsgId`；导出/备份/推送用 `X-Idempotency-Key`。
- 时间：`LocalDateTime` `yyyy-MM-dd'T'HH:mm:ss`；血糖单位统一 `mmol/L`；重量 `g`、热量 `kcal`。
- JSON 载荷版本：`data_backup.payload_json`、`chat_context.content_json`、`msg_push_task.target_json/payload_json`、`ocr_task.ocr_result_json` 必须含 `schemaVersion`。

---

## 5. MVP 依赖库表（14 张，字段以 DDL 为准）

| 表 | MVP 用途 | 关键约束 |
|---|---|---|
| `user_account` | 账号（openid/union_id/phone） | `openid`/`union_id`/`phone` 未删除唯一；`account_status` |
| `user_profile` | 昵称/头像/性别 | 1:1 于用户 |
| `user_health` | 健康档案（类型/确诊年月/用药/运动/目标值） | 1:1 于用户 |
| `user_setting` | 偏好（字号/主题/提醒开关/免责声明） | 1:1 于用户 |
| `user_session` | C 端会话 | 登录签发，登出置 `session_status=0` |
| `auth_sms_code` | 短信验证码 | 短 TTL、禁止日志打印原文 |
| `dict_glucose_node` | 九时段字典 | `is_meal` 区分餐点/非餐点，血糖写入须为 0、饮食写入须为 1 |
| `media_file` | 上传媒体 | `biz_type` CHECK 1~6；`sha256_hex` 用于去重 |
| `glucose_record` | 血糖记录 | `uk_user_date_node(user_id, record_date, node_code, is_deleted)`；`source_type` 1/2/3 |
| `meal_record` | 饮食记录 | 同结构；含 `predict_now`/`predict_suggest`/`suggest_text` |
| `meal_food_item` | 饮食明细 | 关联 `meal_id`，营养快照 |
| `ocr_task` | OCR 任务 | 状态机 + `ocr_result_json`（含 schemaVersion） |
| `food_category` / `food_nutrition` | 食物库（只读） | `shelf_status=1` 才对外返回 |
| `cms_banner` / `cms_news` | 首页与资讯（只读） | `shelf_status` / `publish_status` |
| `feedback` | 反馈与回复 | `reply_status` 0~3 |
| `data_export_task` / `data_backup` | 导出与备份 | `export_scope` 1/2/3；`payload_json` 含 schemaVersion |
| `sys_config` | 公开配置（2.13） | 仅公开分组 GLUCOSE/TEXT/APPEARANCE/NOTIFY |
| `ops_audit_log` | 后台留痕（本 MVP 不直接调用，后台接口触发） | 只追加 |

---

## 6. 联调顺序（前端 → 后端）

1. 启动即调 `2.13 公开配置` 与 `2.12 九时段字典`，缓存到本地；
2. 登录（2.1/2.2/2.3），保存 `accessToken`；未完善资料时引导 2.7/2.8；
3. 进入首页：`2.26 Banner` / `2.27 资讯列表` / `2.16 日看板`；
4. 记录餐前血糖：`2.17 by-node`（`sourceType=1`），刷新 2.16 校验格子状态；
5. 拍照记录饮食：`2.14 → 上传 → 2.15 登记 → 2.21 OCR 任务 → 2.22 轮询 → 用户确认 → 2.19 by-node`；
6. 预估：`2.20 饮食分析` 后从 `meal_record` 读回 `predictNow/predictSuggest/suggestText`，前端展示"现状 vs 建议"；
7. 餐后血糖：`2.17 by-node` 写餐后节点；
8. 统计页：`2.18 血糖统计摘要`（日历 level / 月统计 / 周曲线 / 分时段均值）+ `2.16` 日详情；
9. 反馈：`2.31 → 2.32`；
10. 数据出口：`2.33 → 2.34 查询/下载`；`2.35 备份 → 2.36 恢复`。

---

## 7. MVP 验收标准

| 类别 | 验收项 |
|---|---|
| 功能 | 第 3.1 节 26 个接口全部可用；九时段 by-node 幂等（同日同节点重复提交为覆盖更新）；OCR/分析结果必须经用户确认后入库 |
| 数据 | 所有写入落点与第 5 章库表一致；软删/唯一键/枚举 CHECK 生效；JSON 载荷含 `schemaVersion` |
| 安全 | 口令/验证码/API Key 不落日志；媒体访问用短时效 URL；导出/备份含健康数据时留审计 |
| 性能 | 普通接口 P95 ≤ 500ms；日看板/统计摘要 ≤ 1s；OCR 轮询接口 ≤ 300ms；饮食分析超时 15s 有降级提示 |
| 一致性 | 前端不得本地合并"血糖/饮食混存模型"；一律以 2.16 日看板与 2.18 摘要为准 |

---

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-04 | 首版 MVP 草案（含 `ai_task`/`prediction_record`/`meal_response`、手机号+密码登录、时间型血糖模型） |
| **v2** | **2026-09-15** | **对齐 38 表 DDL、数据库设计文档与接口设计文档**：修正登录口径、表名、九时段节点模型、OCR 任务替代 `ai_task`、饮食分析替代独立预测表、统计摘要替代 overview/trend；补充 MVP 接口清单、库表落点、联调顺序与验收标准 |
| **v2.1** | **2026-09-15** | **修正 3.2 节笔误**：`glucose_record` 无 `provider_code` / `external_record_id` 列，设备直连需先走库表变更（与《AI 模块开发方案》9.5 一致） |
