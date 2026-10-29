# AI 模块数据表说明（逐列）

> 依据：开发库 `sugar_health`（Flyway v43）的 `information_schema` 实测结构 + 迁移文件 + 实体类注释。
> 与 `AI-模块实现说明.md` 配套：那份讲「类怎么协作」，这份讲「数据落在哪、每一列是什么意思」。

- 实测导出方式：`information_schema.COLUMNS / STATISTICS / TABLES`（`TABLE_SCHEMA='sugar_health'`）
- 行数为 2026-10-27 开发库快照，只用于判断「这张表是不是活的」，不代表生产量
- 约定：`~` 表示该列无默认值（NULL 或由应用写入）

---

## 0. 总览

### 0.1 `ai` 包实体直接对应的 9 张表

| # | 表 | 中文说明 | 首次创建 | 后续改动 | 开发库行数 |
| --- | --- | --- | --- | --- | --- |
| 1 | `ai_model_config` | AI 模型配置表 | V1 | V13（运行期配置：`config_version`/`thinking`/`top_p`/`extra_params`/测试结果列/`cipher_algo`/`key_version`） | 4 |
| 2 | `ai_prompt_template` | AI 提示词模板表 | V1 | V18（管理面：`variables_json`/`change_note`/`config_version`） | 11 |
| 3 | `ai_scene_config` | 场景 → 模型与场景级参数 | V19 | — | 4 |
| 4 | `chat_session` | 聊天会话（含滚动摘要与窗口参数） | V1 | V11（`active_user_id` 生成列）、V21（生命周期） | 102 |
| 5 | `chat_message` | 聊天消息（含语音与 token 计量） | V1 | V9（`media_caption`）、V11（幂等/顺序号唯一键） | 937 |
| 6 | `chat_context` | 上下文分片元数据（替代 `chat_context_snapshot`） | V1 | V28（只留元数据，`content_*` 不再写） | 26 |
| 7 | `chat_tool_call` | 会话内工具调用元数据 | V21 | V22（`short` 化 + `result_digest`） | 26 |
| 8 | `chat_user_memory` | 跨会话长期记忆 | V1 | — | 2 |
| 9 | `ocr_task` | OCR 识别任务 | V1 | — | 0 |

### 0.2 AI 相关但不在 `ai` 包的表

| 表 | 归属 | AI 相关的部分 |
| --- | --- | --- |
| `ops_audit_log` | 运营审计（③ 期加的查询接口） | 运营侧所有 AI 配置变更/查询的审计流水（只追加） |
| `community_moderation_hit` | 社区审核（V43） | AI 审核命中的证据明细（只追加、不存原文） |
| `community_article` | 社区（V31 + V43） | `state`/`revision` + 6 个 `ai_*` 列（文章先审后发） |
| `community_comment` | 社区（V29 + V43） | 11 个 AI 列 + `is_hidden`（评论先发后审） |

### 0.3 关系图

```
user_account(1) ──< chat_session(1) ──< chat_message
                        │                    │
                        │                    └──< chat_context（trigger_msg_id / assistant_msg_id）
                        ├──< chat_context
                        ├──< chat_tool_call（session_id + user_msg_id）
                        └──  chat_session.context_summary / summary_upto_msg_id（摘要指针）

user_account(1) ──< chat_user_memory（user_id + memory_key 唯一）
user_account(1) ──< ocr_task（user_id + media_id → media_file）

ai_model_config(1) ──< ai_scene_config.model_code（逻辑外键，无物理约束）
ai_model_config(1) ──< ai_prompt_template.model_code（可空=用默认模型）
ai_prompt_template：scene_code + version_no 唯一（同场景多版本并存，is_enabled 选生效版）

cms_news(1) ──1 community_article ──< community_moderation_hit(target_type='ARTICLE', target_id=news_id)
community_comment(1) ──< community_moderation_hit(target_type='COMMENT', target_id=comment_id)
```

**没有物理外键**：全库靠应用层 + 唯一键保证一致性（`community` 模块的删除一致性用 CHECK 约束，AI 表用唯一键 + 逻辑删除）。

---

## 1. 公共列约定（先看这个，后面表里不再重复解释）

几乎所有表末尾都有这 7 列（`ops_audit_log`、`community_moderation_hit`、`chat_tool_call` 除外）：

| 列 | 类型 | 含义 |
| --- | --- | --- |
| `created_by` | `bigint unsigned` | 创建人（C 端表存 user_id，管理端表存 ops_id，系统写入为 0） |
| `gmt_create` | `datetime(3)` | 创建时间，库侧默认 `CURRENT_TIMESTAMP(3)` |
| `updated_by` | `bigint unsigned` | 最后更新人 |
| `gmt_modified` | `datetime(3)` | 最后更新时间，`ON UPDATE CURRENT_TIMESTAMP(3)` |
| `deleted_by` | `bigint unsigned` | 逻辑删除操作人（NULL=未删） |
| `deleted_at` | `datetime(3)` | 逻辑删除时间（NULL=未删） |
| `is_deleted` | `tinyint unsigned` | 逻辑删除标记 0 否 1 是，默认 0 |

三条由此派生的规则：

1. **唯一键一律带 `is_deleted`**：如 `uk_scene_ver_active(scene_code, version_no, is_deleted)`。因为删掉（`is_deleted=1`）之后要能重建同版本号。
2. **`datetime(3)` 毫秒精度**：`publish_at` 用 `NOW(3)`、比较也用毫秒——踩过「同一秒内 `CURRENT_TIMESTAMP` 判 404」的坑。
3. **逻辑删除必须成对写**：`is_deleted=1` 时 `deleted_at` 不能为 NULL（`cms_news` 上有 `chk_cn_del_consistent` 这类 CHECK 在管，AI 表靠应用层）。

---

## 2. `ai_model_config` — AI 模型配置表

**用途**：模型目录 + 运行期参数。`AiRuntimeConfigServiceImpl.snapshot()` 读它生成不可变快照，`DynamicChatModel` 每次调用据此解析真实模型；管理端 `AdminAiModelController` 增删改 + 连通性测试。

**索引**：`PRIMARY(id)`、`uk_ai_model_code(model_code,is_deleted)`、`uk_model_code_active(model_code,is_deleted)`、`idx_enabled(is_enabled,is_default)`
> ⚠️ `uk_ai_model_code` 与 `uk_model_code_active` 列完全相同，是 V13 改名时留下的冗余索引，功能无影响（可以删一个，但要先确认没有迁移依赖名字）。

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 主键 |
| 2 | `model_code` | varchar(64) | NO | — | 模型编码，业务唯一键；场景绑定/提示词绑定的都是它 |
| 3 | `model_name` | varchar(64) | NO | — | 后台展示名称 |
| 4 | `provider_code` | tinyint unsigned | NO | 1 | 厂商：1 自研、2 OpenAI 兼容、3 通义、4 文心、5 其他；`ChatModelFactory` 据此选构建器 |
| 5 | `api_base_url` | varchar(512) | YES | — | API Base URL；空则用 SDK 默认 |
| 6 | `api_key_cipher` | varchar(512) | YES | — | API Key 密文（AES-256-GCM），**禁止明文**；`AiModelCipher.decryptOrNull` 解 |
| 7 | `model_endpoint` | varchar(128) | YES | — | 真正发给厂商的模型名/部署名（如 `deepseek-chat`、`qwen3-vl-plus`） |
| 8 | `temperature` | decimal(3,2) | NO | 0.70 | 采样温度 |
| 9 | `max_tokens` | int unsigned | NO | 2048 | 最大输出 token |
| 10 | `timeout_ms` | int unsigned | NO | 30000 | 单次调用超时（毫秒） |
| 11 | `is_default` | tinyint unsigned | NO | 0 | 是否默认模型：默认标记是通道默认判定的第一优先级 |
| 12 | `is_enabled` | tinyint unsigned | NO | 1 | 是否启用；停用行不进快照 |
| 13 | `remark` | varchar(255) | YES | — | 备注 |
| 14 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 15 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 16 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 17 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间（autoupdate） |
| 18 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 19 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 20 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |
| 21 | `cipher_algo` | varchar(32) | YES | — | 密钥加密算法（如 `AES-GCM`）；NULL = 明文遗留或未配置 |
| 22 | `key_version` | int unsigned | YES | — | 加密主密钥版本，支持轮换（`AiModelCipher.currentKeyVersion()`） |
| 23 | `top_p` | decimal(3,2) | YES | — | 核采样；NULL = 用 provider 默认 |
| 24 | `thinking` | tinyint(1) | YES | — | 是否开推理链（DeepSeek 专有）；NULL = 不显式设置（注意这里是**可空三态**，不是 0/1） |
| 25 | `extra_params` | json | YES | — | 厂商特有参数，避免为每家加列 |
| 26 | `config_version` | bigint unsigned | NO | 1 | 配置版本：乐观锁 + 缓存失效判据 |
| 27 | `last_test_at` | datetime(3) | YES | — | 最近一次连通性测试时间 |
| 28 | `last_test_ok` | tinyint(1) | YES | — | 最近一次测试是否成功（NULL = 从没测过） |
| 29 | `last_test_message` | varchar(512) | YES | — | 测试结果摘要（脱敏，不含密钥；`ChatModelFactory.classify` 的可读分类写这里） |

**当前开发库的 4 行**（`api_key_cipher` 全为 NULL：这个环境的密钥走 yaml/环境变量，不走库存密文）：

| model_code | provider | model_endpoint | temp | max_tokens | timeout | is_default | is_enabled |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `default_chat` | 1 | `glucopilot-chat-v1` | 0.70 | 2048 | 30000 | 0 | 0 |
| `deepseek-v4-flash` | 1 | `deepseek-v4-flash` | 0.70 | 2048 | 60000 | 0 | 0 |
| `qwen3-vl-plus` | 2 | `qwen3-vl-plus` | 0.20 | 512 | 60000 | 0 | 1 |
| `deepseek-v4-pro` | 1 | `deepseek-v4-pro` | 0.70 | 2048 | 60000 | **1** | 1 |

> 只有 `deepseek-v4-pro` 既是启用又是默认 → 它就是「通道默认」；`qwen3-vl-plus` 虽然启用，但会被识别为视觉模型（`model_endpoint == ai.vision.model`）而在文本候选中被排除。

---

## 3. `ai_prompt_template` — AI 提示词模板表

**用途**：库优先提示词，`AiPromptTemplateServiceImpl` 启动 `load()` 读它，`DynamicPromptAdvisor` 每请求现取。classpath `prompts/<scene>/system.md` 只作兜底。

**索引**：`PRIMARY(id)`、`uk_scene_ver_active(scene_code,version_no,is_deleted)`、`idx_scene_en(scene_code,is_enabled)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 主键 |
| 2 | `scene_code` | varchar(64) | NO | — | 场景编码，对应 `SceneCode.getCode()`（`CHAT`/`CHAT_IMAGE`/`MEAL_ANALYSIS`/`OCR_GLUCOSE`/`SUMMARY`/`MEMORY`/`MODERATION`）；比较用 `BINARY` 区分大小写 |
| 3 | `template_name` | varchar(64) | NO | — | 模板名称（人看的） |
| 4 | `prompt_body` | text | NO | — | 提示词正文；**不能出现成对花括号**（StringTemplate 占位符语法） |
| 5 | `model_code` | varchar(64) | YES | — | 绑定模型；NULL = 用场景/通道默认 |
| 6 | `version_no` | int unsigned | NO | 1 | 版本号；同场景可并存多版，靠 `is_enabled` 选生效版 |
| 7 | `is_enabled` | tinyint unsigned | NO | 1 | 是否启用 |
| 8 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 9 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 10 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 11 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 12 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 13 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 14 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |
| 15 | `variables_json` | text | YES | — | 模板变量清单（JSON 数组，如 `["user_profile"]`）；保存前据此试渲染校验，`[]` 视为无变量 |
| 16 | `change_note` | varchar(255) | YES | — | 改动说明（审计与回溯用，如 `V43：…`） |
| 17 | `config_version` | bigint unsigned | NO | 1 | 乐观锁版本，任何写操作 +1 |

**当前开发库的 11 行**（正好演示「同场景多版本靠 `is_enabled` 选生效版」和「legacy 行保留但不启用」）：

| scene_code | version | 启用 | 正文长度 |
| --- | --- | --- | --- |
| `CHAT` | 1 | ✅ | 1651 |
| `CHAT` | 2 | ❌（草稿/回滚点） | 1624 |
| `CHAT_IMAGE` | 1 | ✅ | 935 |
| `MEAL_ANALYSIS` | 1 | ✅ | 515 |
| `MEMORY` | 1 | ✅ | 1220 |
| `MODERATION` | 1 | ✅ | **880** |
| `OCR_GLUCOSE` | 1 | ✅ | 710 |
| `SUMMARY` | 1 | ✅ | 781 |
| `legacy_chat_system` | 1 | ❌ | 40 |
| `legacy_meal_analysis` | 1 | ❌ | 26 |
| `legacy_glucose_advice` | 1 | ❌ | 27 |

> `scene_code` 用的是 `SceneCode` 枚举名（大写），与 yaml 的 `ai.scenes.<小写 key>` 不是一套写法——库里按枚举 `getCode()` 存，比较时用 `BINARY` 区分大小写。`legacy_*` 三行是早期场景编码，已停用但保留以便回溯。

---

## 4. `ai_scene_config` — 场景配置

**用途**：场景 → 模型/参数的绑定（DB > yaml > 通道默认）。`AiSceneBindingService` 读它，`SceneModelResolver` 据此算 `optionsBuilder`。

**索引**：`PRIMARY(id)`、`uk_scene_code(scene_code,is_deleted)`、`idx_scene_enabled(is_enabled)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 主键 |
| 2 | `scene_code` | varchar(64) | NO | — | 场景编码，见 `SceneCode.getCode()` |
| 3 | `model_code` | varchar(64) | YES | — | 绑定的模型（`ai_model_config.model_code`）；NULL = 用通道默认 |
| 4 | `temperature` | decimal(3,2) | YES | — | 场景级温度；NULL = 继承模型/默认 |
| 5 | `max_tokens` | int unsigned | YES | — | 场景级输出上限；NULL = 继承 |
| 6 | `thinking` | tinyint(1) | YES | — | 是否开推理链；NULL = 不显式设置（三态） |
| 7 | `is_enabled` | tinyint unsigned | NO | 1 | 是否启用 |
| 8 | `config_version` | bigint unsigned | NO | 1 | 乐观锁版本，任何写操作 +1 |
| 9 | `remark` | varchar(255) | YES | — | 备注 |
| 10 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 11 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 12 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 13 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 14 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 15 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 16 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |

> 参数优先级：`ai_scene_config` 非空字段 > yaml `ai.scenes.<scene>.*` > `ai_model_config` 本身。

**当前开发库的 4 行**（`model_code` 全为 NULL = 都用通道默认；只有视觉与饮食两个场景调了参数）：

| scene_code | model_code | temperature | max_tokens | thinking | config_version |
| --- | --- | --- | --- | --- | --- |
| `CHAT` | NULL | NULL | NULL | NULL | 25（改过很多次） |
| `CHAT_IMAGE` | NULL | 0.20 | 512 | NULL | 1 |
| `MEAL_ANALYSIS` | NULL | 0.10 | NULL | NULL | 1 |
| `OCR_GLUCOSE` | NULL | NULL | NULL | NULL | 1 |

> 注意 `CHAT_IMAGE` 的 `max_tokens=512` 是**有意压低**的：阶段 1 只要一段图片描述，不需要长回答；`MEAL_ANALYSIS` 的 0.10 温度也是同一个理由（要稳定的结构化输出，不要发挥）。

---

## 5. `chat_session` — 聊天会话表

**用途**：一个会话一行。列表、置顶、归档、滚动摘要都在这。`ChatSessionServiceImpl` 是唯一写入方。

**索引**：`PRIMARY(id)`、`uk_user_active(active_user_id)`、`idx_user_last(user_id,last_msg_at)`、`idx_user_pin(user_id,is_pinned,last_msg_at)`、`idx_user_status(user_id,session_status)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 会话 ID（也就是 `ChatMemory` 的 conversationId 的字符串形式） |
| 2 | `user_id` | bigint unsigned | NO | — | 归属用户（`user_account.id`） |
| 3 | `title` | varchar(128) | YES | — | 标题；默认名由首句自动生成（`renameIfDefault`） |
| 4 | `session_status` | tinyint unsigned | NO | 1 | 0 归档、1 进行中、2 已结束；`requireActive` 只放行 1 |
| 5 | `session_type` | tinyint unsigned | NO | 3 | 1 纯文字、2 纯语音、3 混合 |
| 6 | `model_code` | varchar(64) | YES | — | 会话默认模型（创建时定，不随配置漂移） |
| 7 | `system_prompt_ver` | varchar(32) | YES | — | 本轮/本会话用的提示词版本标签（如 `chat@v1`），用于「这条回复用的是哪版提示词」回溯 |
| 8 | `context_summary` | mediumtext | YES | — | 滚动摘要正文（超长对话压缩后供模型续用） |
| 9 | `summary_upto_msg_id` | bigint unsigned | YES | — | 摘要已覆盖至的消息 ID；`updateSummary ... where upto < ?` 的乐观并发指针 |
| 10 | `window_msg_count` | smallint unsigned | NO | 20 | **已废弃（V21）**：窗口统一由 `ai.memory.window-messages` 决定，读取侧不再读本列 |
| 11 | `msg_count` | int unsigned | NO | 0 | 消息条数冗余（`touchOnMessage` 时 +1） |
| 12 | `last_msg_at` | datetime(3) | YES | — | 最近消息时间；列表排序 + 惰性归档判据 |
| 13 | `is_pinned` | tinyint unsigned | NO | 0 | 是否置顶 |
| 14 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 15 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 16 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 17 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 18 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 19 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 20 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |
| 21 | `active_user_id` | bigint unsigned | YES | **生成列** | `STORED GENERATED`：仅「进行中且未删除」的会话等于 `user_id`，其余为 NULL；配合 `uk_user_active` 保证**每用户至多一条进行中会话** |

> 第 21 列是 V11 用来替代「应用层查一次再插入」的：并发两个「开新会话」请求时，第二个会被唯一键挡住，而不是插出两条进行中会话。

---

## 6. `chat_message` — 聊天消息表

**用途**：对话正文 + 多模态引用 + token 计量。`ChatMessageServiceImpl` 写，`ChatCompletionServiceImpl` 读/写。

**索引**：`PRIMARY(id)`、`uk_session_seq(session_id,seq_no)`、`uk_session_client_active(session_id,client_msg_id,is_deleted)`、`idx_session_seq`、`idx_session_time(session_id,gmt_create)`、`idx_reply_to(reply_to_msg_id)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 消息 ID |
| 2 | `session_id` | bigint unsigned | NO | — | 会话 ID |
| 3 | `user_id` | bigint unsigned | NO | — | 归属用户（冗余，省一次 join） |
| 4 | `seq_no` | int unsigned | NO | 0 | 会话内展示用顺序号，从 1 连续；`(seq+1)/2` 反推轮次 |
| 5 | `msg_role` | tinyint unsigned | NO | — | 1 用户、2 助手、3 系统 |
| 6 | `msg_type` | tinyint unsigned | NO | 1 | 1 文本、2 图片、3 语音；**payload 完整性由应用层保证**（V1 的 CHECK 已移除） |
| 7 | `content_text` | text | YES | — | 文本内容；图片轮为空、助手回复必填 |
| 8 | `media_id` | bigint unsigned | YES | — | 图片文件 ID（`media_file.id`） |
| 9 | `voice_media_id` | bigint unsigned | YES | — | 语音文件 ID |
| 10 | `asr_text` | varchar(512) | YES | — | 语音识别文本（ASR 结果，与 `content_text` 分开存） |
| 11 | `media_caption` | varchar(1024) | YES | — | 图片内容摘要：多模态**阶段 1** 产物，供会话记忆回放（`IMAGE_CAPTION_PREFIX`） |
| 12 | `voice_duration_ms` | int unsigned | YES | — | 语音时长毫秒 |
| 13 | `reply_to_msg_id` | bigint unsigned | YES | — | 回复/引用哪条消息（助手回复指向本轮用户消息） |
| 14 | `model_code` | varchar(64) | YES | — | 本条助手回复**实际**用的模型（取响应元数据，不是配置值） |
| 15 | `prompt_tokens` | int unsigned | YES | — | 提示 token 数（助手消息） |
| 16 | `completion_tokens` | int unsigned | YES | — | 生成 token 数（助手消息） |
| 17 | `finish_reason` | tinyint unsigned | YES | — | 1 正常、2 长度截断、3 内容过滤、4 错误 |
| 18 | `latency_ms` | int unsigned | YES | — | 模型耗时毫秒（首字节到末帧） |
| 19 | `client_msg_id` | varchar(64) | YES | — | 客户端幂等 ID；重发时应用层捕获 MySQL 1062 当成功返回既有消息 |
| 20 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 21 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 22 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 23 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 24 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 25 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 26 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |

> 两个唯一键各管一件事：`uk_session_seq` 保证顺序号不撞（`insertWithSeqNo` 重试的依据）；`uk_session_client_active` 保证前端重试不会插两条。

---

## 7. `chat_context` — 上下文分片元数据表

**用途**：③ 期改造后**只存元数据**（分片类型/token 估算/条数），让「这一轮上下文到底装了什么」可查，同时不把用户健康文本二次落库。`ChatContextRecorder` 在助手消息落库后写。

**索引**：`PRIMARY(id)`、`idx_session_round(session_id,context_round,sort_no)`、`idx_trigger_msg`、`idx_assistant_msg`、`idx_user_time(user_id,gmt_create)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 上下文 ID |
| 2 | `session_id` | bigint unsigned | NO | — | 会话 ID |
| 3 | `user_id` | bigint unsigned | NO | — | 用户 ID（冗余，便于按用户审计） |
| 4 | `trigger_msg_id` | bigint unsigned | YES | — | 触发本轮的用户消息 ID |
| 5 | `assistant_msg_id` | bigint unsigned | YES | — | 本轮生成的助手消息 ID（回写；**落库后才拿得到**，所以写入时序在 persist 之后） |
| 6 | `context_round` | int unsigned | NO | 1 | 会话内轮次号，从 1 起；由助手消息 `seq_no` 推 `(seq+1)/2` |
| 7 | `context_type` | tinyint unsigned | NO | — | 1 系统提示、2 健康档案快照、3 历史摘要、4 业务数据、5 工具结果、6 其他 |
| 8 | `context_key` | varchar(64) | YES | — | 分片键，如 `system_prompt`/`health_profile`/`glucose_recent` |
| 9 | `content_text` | mediumtext | YES | — | 文本上下文——**V28 起恒 NULL**（保留列以兼容历史数据与回滚） |
| 10 | `content_json` | json | YES | — | 结构化上下文——**V28 起恒 NULL**，同上 |
| 11 | `token_estimate` | int unsigned | YES | — | 本分片预估 token |
| 12 | `model_code` | varchar(64) | YES | — | 本轮调用模型编码 |
| 13 | `sort_no` | int unsigned | NO | 0 | 同轮内排序；固定顺序「系统提示 → 长期记忆 → 摘要 → 工具摘要 → 历史消息」 |
| 14 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 15 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 16 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 17 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 18 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 19 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 20 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |

> 注意：这一列集说明里**没有**「装了哪几条历史消息」的字段——历史片只记 `token_estimate` 与条数（在 `context_key` 里表达为 `history`），不记具体消息 ID 列表。原因是不想让「上下文快照」变成第二份对话正文。

---

## 8. `chat_tool_call` — 工具调用元数据表

**用途**：只记「谁被调了 / 成功与否 / 耗时 / 结果摘要」，**不存完整返回**。`ToolCallTraceRecorder` 写，`ChatContextReader.recentToolDigests` 读（回放给模型）。

**索引**：`PRIMARY(id)`、`idx_ctc_session(session_id,gmt_create)`、`idx_ctc_user_msg(user_msg_id)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 主键 |
| 2 | `session_id` | bigint unsigned | NO | — | 会话 ID |
| 3 | `user_id` | bigint unsigned | NO | — | 用户 ID |
| 4 | `user_msg_id` | bigint unsigned | YES | — | 本轮用户消息 ID；同一轮多次工具调用共享它 |
| 5 | `round_key` | varchar(64) | YES | — | 本轮单飞键 `chat-round:<userMsgId>`，排障时与日志对齐 |
| 6 | `tool_name` | varchar(64) | NO | — | 工具名，与 `@Tool(name=...)` 一致 |
| 7 | `call_index` | smallint unsigned | NO | 1 | 同一轮内第几次调用，从 1 开始（`CALL_COUNTER` 按轮隔离） |
| 8 | `ok` | tinyint unsigned | NO | 1 | 0 失败、1 成功 |
| 9 | `duration_ms` | int unsigned | YES | — | 执行耗时毫秒（夹到 int 上限） |
| 10 | `error_type` | varchar(128) | YES | — | 失败时的**异常类名**（只记类名不记消息正文，避免把用户数据写进库） |
| 11 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 发生时间 |
| 12 | `result_digest` | varchar(512) | YES | — | 结果关键字段摘要（≤512 字，`ai.memory.tool-digest-max-chars`）；刻意不存完整返回 |

> 这张表**刻意没有** `content`/`result` 列：工具返回里常常是用户的血糖/饮食明细，落全量等于把健康数据复制一份到审计表。要复现问题用 `tool_name + ok + error_type + gmt_create` 去对日志。

---

## 9. `chat_user_memory` — 跨会话长期记忆表

**用途**：`UserChatMemoryServiceImpl` 抽取/更新，`forInjection` 读出来注入系统提示词。

**索引**：`PRIMARY(id)`、`uk_user_key_active(user_id,memory_key,is_deleted)`、`idx_user(user_id)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 记忆 ID |
| 2 | `user_id` | bigint unsigned | NO | — | 用户账号 ID |
| 3 | `memory_key` | varchar(64) | NO | — | 记忆键（如 `diet_taboo`、`allergy`）；同用户同 key 唯一（`upsert` 的依据） |
| 4 | `memory_text` | varchar(512) | NO | — | 记忆内容（自然语言短句，如「不吃香菜」） |
| 5 | `source_session_id` | bigint unsigned | YES | — | 来源会话，可空（用户手工加的记忆没有来源会话） |
| 6 | `confidence` | tinyint unsigned | NO | 100 | 置信度 0–100；注入时按它排序取前 N，同 key 冲突时取高的 |
| 7 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 8 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 9 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 10 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 11 | `deleted_by` | bigint unsigned | YES | — | 删除人（用户自助删除时写 NULL） |
| 12 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 13 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |

---

## 10. `ocr_task` — OCR 识别任务表

**用途**：血糖仪照片识别是异步的（`POST /api/v1/ocr-tasks` 建任务，前端轮询 `GET /{id}`）。`OcrTaskServiceImpl` 建任务后丢线程池跑，结果写 `ocr_result_json`。

**索引**：`PRIMARY(id)`、`idx_user_status(user_id,task_status)`、`idx_media(media_id)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 任务 ID |
| 2 | `user_id` | bigint unsigned | NO | — | 用户账号 ID |
| 3 | `media_id` | bigint unsigned | NO | — | 源图片 ID（`media_file.id`） |
| 4 | `biz_type` | tinyint unsigned | NO | — | 业务类型：1 血糖、2 餐食 |
| 5 | `task_status` | tinyint unsigned | NO | 0 | 0 待处理、1 进行中、2 成功、3 失败（对齐 `CodeEnum.TaskStatus`） |
| 6 | `ocr_result_json` | longtext | YES | — | 识别结果 JSON（`OcrDtos` 结构） |
| 7 | `error_msg` | varchar(512) | YES | — | 失败原因（可读文案，不泄堆栈） |
| 8 | `finished_at` | datetime(3) | YES | — | 完成时间（成功/失败都写） |
| 9 | `created_by` | bigint unsigned | NO | 0 | 创建人 |
| 10 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |
| 11 | `updated_by` | bigint unsigned | NO | 0 | 更新人 |
| 12 | `gmt_modified` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 修改时间 |
| 13 | `deleted_by` | bigint unsigned | YES | — | 删除人 |
| 14 | `deleted_at` | datetime(3) | YES | — | 删除时间 |
| 15 | `is_deleted` | tinyint unsigned | NO | 0 | 逻辑删除 |

---

## 11. `ops_audit_log` — 运营操作审计表

**用途**：③ 期「AI 合规」的落地载体之一：运营对所有模块（含 `ai`）的读写流水，只追加、不可改。`GET /admin/v1/ops-audit-logs`（`ai:audit:view`）只读查询。

**索引**：`PRIMARY(id)`、`idx_ops_time(ops_id,gmt_create)`、`idx_module_action(module_code,action_code)`、`idx_target(target_type,target_id)`、`idx_gmt_create`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint unsigned | NO | 自增 | 审计 ID |
| 2 | `ops_id` | bigint unsigned | NO | — | 运营账号 ID |
| 3 | `operator_name` | varchar(64) | NO | — | 操作人账号冗余（账号改名后仍能看当时是谁） |
| 4 | `role_code` | varchar(64) | YES | — | 操作时的角色编码 |
| 5 | `module_code` | varchar(32) | NO | — | 模块：`dashboard`/`news`/`banner`/`media`/`food`/`feedback`/`export`/`user`/`archive`/`system`/`push`/`ai` |
| 6 | `action_code` | varchar(64) | NO | — | 动作编码（如 `feedback_reply`、`ai_prompt_publish`） |
| 7 | `action_name` | varchar(64) | NO | — | 动作中文名 |
| 8 | `target_type` | varchar(32) | NO | — | 目标类型（如 `feedback`、`ai_model`） |
| 9 | `target_id` | bigint unsigned | NO | 0 | 目标主键；0 = 无具体目标（列表查询类） |
| 10 | `detail_json` | varchar(2000) | YES | — | 详情 JSON（变更前后摘要，**不含密钥与用户健康数据**） |
| 11 | `request_uri` | varchar(255) | YES | — | 请求 URI |
| 12 | `request_method` | varchar(10) | YES | — | HTTP 方法 |
| 13 | `client_ip` | varchar(45) | YES | — | 客户端 IP（45 位兼容 IPv6） |
| 14 | `cost_ms` | int unsigned | YES | — | 处理耗时毫秒 |
| 15 | `result_code` | int | YES | — | 业务返回码 |
| 16 | `created_by` | bigint unsigned | NO | 0 | 创建人（通常等于 `ops_id`） |
| 17 | `gmt_create` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间 |

> 为什么没有 `gmt_modified`/`is_deleted`：审计表**只追加**，不允许更新或删除（连逻辑删除都不给，否则「删掉审计」就成了绕过手段）。

---

## 12. `community_moderation_hit` — 审核命中明细（V43）

**用途**：AI 审核的证据表，只追加。**只存元数据，不存原文**——存偏移量与片段哈希，需要复核时用原文重新切片比对。

**索引**：`PRIMARY(id)`、`idx_cmh_target(target_type,target_id,id)`、`idx_cmh_category(category_code,created_at)`

| # | 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `id` | bigint | NO | 自增 | 主键 |
| 2 | `target_type` | varchar(16) | NO | — | 审核对象类型：`ARTICLE` / `COMMENT` |
| 3 | `target_id` | bigint | NO | — | 业务主键（`ARTICLE`→`cms_news.id`；`COMMENT`→`community_comment.id`） |
| 4 | `revision` | int | YES | — | 文章版本号；评论为空（评论没有 revision） |
| 5 | `hit_source` | varchar(16) | NO | `AI` | 命中来源，目前恒 `AI`（预留规则层/人工） |
| 6 | `category_code` | varchar(32) | NO | — | 主命中类别 code，见 `ModerationCategories`（`medical_advice` 等 12 类） |
| 7 | `severity` | tinyint | NO | — | 严重度：1 低、2 中、3 高 |
| 8 | `confidence` | tinyint | YES | — | 模型置信度 0–100 |
| 9 | `start_offset` | int | YES | — | 命中起始位置（**原文 1 基字符偏移**）；定位失败为 NULL |
| 10 | `end_offset` | int | YES | — | 命中结束位置（不含，左闭右开） |
| 11 | `snippet_hash` | char(64) | YES | — | 命中片段 SHA-256（**不存原文**，复核时用原文按偏移重算比对） |
| 12 | `snippet_len` | int | YES | — | 命中片段字符数 |
| 13 | `reason` | varchar(500) | NO | `''` | 模型给出的说明（不摘抄原文） |
| 14 | `model_code` | varchar(128) | NO | `''` | 出结论的模型编码（取响应元数据） |
| 15 | `prompt_ver` | varchar(32) | YES | — | 提示词版本标签（如 `moderation@v1`），复现用 |
| 16 | `created_at` | datetime(3) | NO | CURRENT_TIMESTAMP(3) | 创建时间（只有这一列时间，无 `gmt_modified`） |

---

## 13. 社区表上的 AI 列（V43）

### 13.1 `community_article`（文章：先审后发）

| 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `state` | varchar(24) | NO | `AI_PENDING` | 文章状态机；AI 审核相关：`AI_PENDING`→`AI_REVIEWING`→`PENDING_ADMIN`（AI 通过，待人工终审）/`AI_REJECTED`/`AI_ERROR` |
| `revision` | int | NO | 1 | 版本号；每次改稿 +1，`queue.finish` 用它做条件更新（防止旧版本的结论覆盖新版本） |
| `ai_verdict` | varchar(16) | NO | `PENDING` | AI 结论：`PENDING`/`PASS`/`REJECT`/`ERROR` |
| `ai_reason` | varchar(1000) | NO | `''` | 结论说明（中文，不摘抄原文） |
| `ai_model` | varchar(128) | NO | `''` | 出结论的模型编码（响应元数据） |
| `ai_checked_at` | datetime(3) | YES | — | 出结论时间 |
| `ai_category` | varchar(32) | YES | — | 主命中类别 code，见 `ModerationCategories` |
| `ai_severity` | tinyint | YES | — | 严重度 1低 / 2中 / 3高 |
| `ai_confidence` | tinyint | YES | — | 模型置信度 0–100 |
| `ai_prompt_ver` | varchar(32) | YES | — | 提示词版本标签（如 `moderation@v1`），复现用 |

### 13.2 `community_comment`（评论：先发后审）

| 列 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `ai_status` | varchar(16) | NO | `PENDING` | 审核任务状态：`PENDING`/`REVIEWING`/`DONE`/`SKIPPED`（存量未审）。V43 建列时两步默认（先 `SKIPPED` 再加列改 `PENDING`），保证存量评论不被误判 |
| `ai_verdict` | varchar(16) | NO | `''` | `PASS`/`REJECT`/`ERROR`；空 = 尚未出结论 |
| `ai_category` | varchar(32) | YES | — | 主命中类别 code |
| `ai_severity` | tinyint | YES | — | 严重度 1/2/3 |
| `ai_confidence` | tinyint | YES | — | 置信度 0–100 |
| `ai_reason` | varchar(1000) | NO | `''` | 中文说明（不摘抄原文） |
| `ai_model` | varchar(128) | NO | `''` | 本轮模型编码 |
| `ai_prompt_ver` | varchar(32) | YES | — | 提示词版本标签 |
| `ai_claimed_at` | datetime(3) | YES | — | 抢占时间，用于僵尸任务回收（>3 分钟退回 `PENDING`） |
| `ai_checked_at` | datetime(3) | YES | — | 出结论时间 |
| `is_hidden` | tinyint | NO | 0 | 1 = 机器判违规已隐藏（先发后审）；管理端恢复只翻这一列，**不动 `ai_verdict`**（保留机器判过的证据） |

> 评论侧还有专门索引 `idx_cc_ai_queue(ai_status, id)` 供 `claimComment` 的 `FOR UPDATE SKIP LOCKED` 使用（先按状态过滤，再按 id 顺序取一条，不需要看 `ai_claimed_at`——僵尸回收单独一条 UPDATE 按时间扫）。

---

## 14. 设计要点与坑（按表）

| 表 | 要点 |
| --- | --- |
| `ai_model_config` | ① `thinking`/`top_p`/`last_test_ok` 都是**可空三态**（NULL ≠ 0）；② `api_key_cipher` 只存密文，`cipher_algo`/`key_version` 支撑主密钥轮换；③ 两个完全相同的唯一索引是历史遗留 |
| `ai_prompt_template` | ① `prompt_body` 不能出现成对花括号（ST 语法）；② 同场景多版本靠 `is_enabled` 选，`uk` 只到 `(scene_code,version_no,is_deleted)`；③ `variables_json` 是保存期校验依据，不是运行时替换来源 |
| `ai_scene_config` | 参数优先级 DB > yaml；NULL 表示「不覆盖」，所以不能用 0 表示「未设置」 |
| `chat_session` | ① `active_user_id` 生成列 + `uk_user_active` 保证单进行中会话；② `window_msg_count` 已废弃；③ `summary_upto_msg_id` 是摘要的乐观并发指针 |
| `chat_message` | ① `seq_no` 靠 `selectNextSeqNo`（**不过滤 is_deleted**）+ 唯一键重试；② `client_msg_id` 幂等；③ `model_code` 存实际调用值，不是配置值 |
| `chat_context` | ① 只存元数据，`content_text`/`content_json` 自 V28 恒 NULL；② 轮次号由 `seq_no` 反推；③ 每轮 1–5 行 |
| `chat_tool_call` | ① 只存 `error_type` 类名与 `result_digest`，不存返回原文；② `call_index` 由 toolContext 里的计数器给，按轮隔离 |
| `chat_user_memory` | ① `uk(user_id,memory_key,is_deleted)` 是 upsert 依据；② `confidence` 决定注入顺序；③ 用户删除时 `deleted_by` 写 NULL（自助操作无运营账号） |
| `ocr_task` | 异步任务表：`PENDING` 建行 → 线程池跑 → `SUCCEEDED`/`FAILED`；前端轮询而不是长连接 |
| `ops_audit_log` | 只追加：无 `gmt_modified`、无 `is_deleted`。查询接口本身不写审计（避免「看审计」把审计刷满） |
| `community_moderation_hit` | 证据表只存偏移 + 哈希，不存原文；`hit_source` 预留规则层 |
| `community_article`/`community_comment` | 文章先审后发（PASS 仍需 `PENDING_ADMIN` 人工终审），评论先发后审（REJECT 即 `is_hidden=1`）；`ERROR` 两边都**不等于通过** |

---

## 15. 想改数据时的注意事项

| 场景 | 操作 | 别忘 |
| --- | --- | --- |
| 新增模型 | 插 `ai_model_config`（`model_code` 唯一、`api_key_cipher` 走加密） | `config_version` 由应用 +1，别手工改；改完等 TTL 或调刷新接口 |
| 换提示词 | 插新版本行 + 把旧版 `is_enabled=0` | 同一场景只能有一个启用版本，否则生效版不确定 |
| 改场景绑定 | 写 `ai_scene_config`，NULL 表示继承 | 别用 0/空串表达「不覆盖」 |
| 删会话 | 逻辑删 `chat_session` + 级联删 `chat_message`/`chat_context`/`chat_tool_call` | 删了 `chat_session` 但留 `chat_message` 会让 `ChatMemory` 读到孤儿消息 |
| 手工重跑审核 | 文章置 `state='AI_PENDING'`；评论置 `ai_status='PENDING'` | 别直接改 `ai_verdict`——`finish` 只认状态列，改了也不生效 |
| 恢复被隐藏评论 | 只翻 `is_hidden=0` | 保留 `ai_verdict='REJECT'` 作为机器判断的证据 |
| 清理测试数据 | 按 `client_msg_id LIKE 'e2e-%'` / `client_request_id LIKE 'e2e-%'` | 直接 `DELETE`（不是逻辑删），否则配额统计把它们算进去 |
