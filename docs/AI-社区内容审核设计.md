# AI 侧社区内容审核 · 技术设计

| 项 | 内容 |
| --- | --- |
| 文档版本 | v1.0（2026-10-19） |
| 归属 | AI 模块（社区内容审核能力与审核台账） |
| 状态 | 设计已定，待评审 → 待实施 |
| 依赖契约 | `docs/api/接口设计文档.md`（本文件定稿后补 4.20~4.24 与 2.40 章节） |
| 相关实现 | `ai/prompt/`、`ai/scene/`、`ai/vision/`、`common/audit/`、`common/idempotency/` |

### 已拍板的决策（本文档全部按此展开）

| # | 决策 | 取值 |
| --- | --- | --- |
| D1 | 发布策略 | **文章：先审后发；评论：先发后审**（按 `content_type` 可配） |
| D2 | 内容形态 | **文章 = 文字 + 配图；评论 = 纯文字**（视频一期不做） |
| D3 | 审核对象 | 文章、评论、昵称、头像、个人简介、私信/群聊消息、举报重审 |
| D4 | 结论落地 | **AI 只出结论 + 回调端口**（`ContentModerationPort`）；可见性字段归社区模块 |
| D5 | 一期范围 | **含人工处置 + 影子模式（shadow）**；申诉放二期（状态机预留） |
| D6 | 社区侧现状 | 社区表与契约未定 → 本文档第 2.3 节给出「社区表最小要求」，供社区侧对齐 |

---

## 1. 背景与目标

社区（文章、点赞、评论、私信）上线后，平台方是内容的第一责任人。AI 侧的价值不是"替运营判断一切"，而是：

1. **把确定性风险挡在机器层**（联系方式、导流、疗效承诺、停药建议），零成本、毫秒级；
2. **把不确定的收敛成人审队列**，让运营只看该看的；
3. **留下可解释、可追溯、可复现的审核台账**（谁、什么时候、依据什么判的）；
4. **失败可降级但不可放行**——这是审核系统与其它 AI 功能最大的区别。

### 非目标（明确不做）

- 不做账号级处罚（禁言/封禁）的自动执行：机器只判内容，处置必须人工（合规要求）。
- 不做端到端的社区业务（发帖、点赞、关注、推荐流）——那是社区模块的职责。
- 不做视频/直播审核（一期）。
- 不引第三方内容安全服务、不引 MQ（保持"DB 队列 + 线程池"的零新增依赖形态）。

---

## 2. 边界划分

### 2.1 职责矩阵

| 事项 | 社区模块 | AI 模块 |
| --- | --- | --- |
| 帖子/评论/私信的表与状态机 | ✅ | — |
| `audit_status`、`visible`、`is_hidden` 等可见性字段 | ✅ 拥有与维护 | 只通过端口给出建议结论 |
| 发布策略（先审后发/先发后审）执行 | ✅ | 提供建议值与开关配置 |
| 规则引擎（关键词/正则/阈值） | — | ✅ |
| 模型判定（文本/图片） | — | ✅ |
| 审核任务队列、重试、补偿 | — | ✅ |
| 人工审核队列与处置接口 | 提供内容原文（受权限控制） | ✅ 提供队列/详情/处置 |
| 命中证据、原因码、可复现信息 | — | ✅ |
| 审核提示词与模型绑定 | — | ✅（走 `ai_prompt_template` / `ai_scene_config`） |
| 账号级处罚（禁言/封禁） | ✅ 人工执行 | 只在结论里给 `risk_level` 与建议 |

### 2.2 依赖方向（单向）

```text
社区模块 ──调用──► AI 模块：ContentModerationService.submit / page / detail / decide
AI 模块 ──端口──► 社区模块：ContentModerationPort.applyDecision(...)
```

- 端口 **定义在 AI 侧**（`ai/moderation/port/ContentModerationPort`），**实现方在社区侧**。
  与仓库已有 `device/port/GlucoseIngestPort` 同一套写法：能力方定义、业务方实现。
- AI 模块**不引用**任何社区实体，社区表结构变更不需要回头改 AI。
- 端口调用失败：只 WARN + 记 `action=9 补偿回写失败`，由补偿任务重投（见 §4.6）。

### 2.3 社区表最小要求（给社区方）

AI 侧通过 `(content_type, target_id)` 多态引用内容，因此业务表至少需要：

| 字段 | 用途 | 备注 |
| --- | --- | --- |
| `id` | `target_id` | BIGINT UNSIGNED |
| `author_id` | 频控、信誉、申诉归属 | 与 `user_account.id` 一致 |
| 正文类字段 | 审核取文本 + 人工复核回查原文 | 文章：`title` + `content`；评论：`content`；私信：`content` |
| 图片/附件 ID 列表 | 图片审核 | 复用 `media_file.id`（`biz_type` 建议新增 `COMMUNITY_IMAGE(6)`） |
| `audit_status` | 业务侧可见性状态 | 建议 `0待审 1通过 2拒绝 3人工介入` |
| `visible` 或等效 | 是否对外可见 | 文章先审后发用 `PENDING`；评论先发后审默认可见 |
| `gmt_create` / `gmt_modified` | 时间线与编辑重审判定 | 编辑导致 `content_hash` 变化即触发重审 |

> 私信/群聊另有额外要求：消息表需带 `chat_id`（会话）与 `msg_seq`，且**正文可加密与否必须先定**——见 §10.4。

---

## 3. 审核对象与策略矩阵

### 3.1 `content_type` 枚举（终态，一期全落库，避免二次迁移）

| code | 含义 | 发布策略 | 是否含图片 | 一期实现 |
| --- | --- | --- | --- | --- |
| 1 | 文章 | **先审后发** | 是（配图） | ✅ |
| 2 | 评论 | **先发后审** | 否 | ✅ |
| 3 | 昵称 | 先审后生效 | 否 | ✅ |
| 4 | 头像 | 先审后生效 | 是（单图） | ✅ |
| 5 | 个人简介 | 先审后生效 | 否 | ✅ |
| 6 | 私信/群聊消息 | 先发后审（实时感） | 否（一期） | ⏳ 若私信同期上线则必须同期实现，见 §14 R1 |

`content_type=6` 的特殊性（一期若做）：
- **单人私信**：违规只影响两人会话 → 默认"仅记录 + 高严重度才隐藏"；
- **群聊**：影响面大 → 与评论同策略；
- **绝不能把私信原文写进审核表或人工页面全文展示**（§10.4）。

### 3.2 `source`（触发来源）

| code | 含义 | 说明 |
| --- | --- | --- |
| 1 | 用户提交 | 首次发布 |
| 2 | 编辑重审 | 内容 `content_hash` 变化后重审（旧任务保留） |
| 3 | 举报重审 | 用户举报触发，不改可见性，只出结论 |
| 4 | 巡检重扫 | 运营批量重扫（规则/提示词升级后回溯） |
| 5 | 申诉 | **二期**，见 §14 R3 |

> 幂等唯一键含 `source`：同一内容被"提交"与"举报"分别审核时是两条独立任务，但结论可复用（§9.3）。

---

## 4. 审核流程

### 4.1 三段式分级

```text
            ┌──────────────┐   命中高危    ┌──────────────┐
内容 ──────► │ L1 规则层     │ ────────────► │ 直接出结论    │ 0 成本、毫秒级
            │ 关键词/正则/  │               └──────────────┘
            │ 长度/白名单   │   未命中或仅记录
            └──────┬───────┘
                   ▼
            ┌──────────────┐   通过/拒绝   ┌──────────────┐
            │ L2 模型层     │ ────────────► │ 出结论 + 证据 │ 有成本、秒~分钟
            │ 文本 / 视觉   │               └──────────────┘
            └──────┬───────┘
                   ▼ 不确定 / 高风险 / 解析失败 / 调用失败
            ┌──────────────┐
            │ L3 人工层     │ 运营队列处置
            └──────────────┘
```

### 4.2 状态机（执行状态与结论分成两列）

```text
audit_status（任务执行状态）
  0 待执行 ──► 1 执行中 ──► 2 已出结论
                   │
                   ├─► 3 待人工（不确定 / 高风险 / 解析失败 / 抽检）
                   ├─► 4 审核失败（重试耗尽）
                   └─► 5 申诉中（二期）──► 2（改判）

final_decision（结论，只前进不回退）
  0 未定 │ 1 机器通过 │ 2 机器拒绝 │ 3 人工通过 │ 4 人工驳回 │ 5 申诉改判通过（二期）
```

**为什么要两列**：`3 待人工` 表示"还没判完"，`2 拒绝` 表示"判完了"。合并后做不了"人工队列按已等待时长排序"，也回答不了"这条到底判了没"。

### 4.3 一次审核的时序

```text
社区模块                        AI 模块                                DB
   │  submit(type,id,payload)      │                                    │
   │──────────────────────────────►│ 1 归一化 + content_hash            │
   │                               │ 2 幂等查（uk 命中→直接返回）────────►│
   │                               │ 3 L1 规则层（同步，毫秒）            │
   │                               │   命中 deny  → final_decision=2    │
   │                               │   命中 manual→ audit_status=3      │
   │                               │   干净      → audit_status=0       │
   │ ◄── TaskView(taskId,status) ──│ 4 入队（executor）+ 落 task         │
   │                               │                                    │
   │                               │ 5 抢占 → audit_status=1,locked_by  │
   │                               │ 6 L2 模型层（截断/分段/图片）        │
   │                               │ 7 解析 + 白名单校验 → hit 明细      │
   │                               │ 8 出结论（或转人工）                │
   │ ◄── applyDecision(...) ───────│ 9 端口回调（幂等；失败→补偿）        │
   │                               │10 写 action 留痕                    │
```

### 4.4 抢占式消费（不引 MQ 的关键）

多实例部署下同一个任务不能被审两次：

```sql
-- 抢占：条件更新，天然防重
UPDATE content_moderation_task
   SET audit_status = 1, locked_by = #{worker}, locked_at = NOW(3), updated_by = 0
 WHERE id = #{id} AND audit_status = 0 AND locked_by IS NULL;
-- 影响 0 行 = 别人抢到了，直接跳过
```

- 主路径：`submit` 之后立刻交给 `moderationExecutor`（仿 `ai/business/ocr/config/OcrTaskConfig` 的线程池写法）。
- 补偿路径：定时扫描 `audit_status = 0 AND (next_retry_at IS NULL OR next_retry_at <= NOW(3))`，`ORDER BY risk 优先 / gmt_create`，`LIMIT 50`。
  ⚠️ 仓库当前**未开启定时调度**（`common/config` 里的 `ScheduleConfig` 是注释掉的），本期需要 `@EnableScheduling` 或交给已有的 ruoyi-quartz；这是实施前置条件之一（§14 R4）。
- 启动补偿：应用启动后把 `audit_status = 1 AND locked_at < NOW() - 10 分钟` 的任务重置为 0（进程崩溃留下的僵尸任务）。

### 4.5 重试与退避

| 失败类型 | 处理 |
| --- | --- |
| 模型超时 / 5xx / 网络 | `attempts+1`，`next_retry_at = now + 30s × 2^attempts`，最多 3 次；耗尽 → `audit_status=4` + 转人工 |
| 输出解析失败 / 类别越界 | **不重试**（重试大概率仍失败），直接 `audit_status=3` 人工 |
| 端口回调失败 | 不影响结论；写 `action=9`，由补偿任务重投（端口实现必须幂等） |
| 内容已删除（业务侧查不到） | 任务置 `is_deleted=1`，不再审核 |

### 4.6 影子模式（`mode=shadow`，上线必备）

| 模式 | L1 规则 | L2 模型 | 是否改可见性 | 用途 |
| --- | --- | --- | --- | --- |
| `off` | 只记录 | 不调用 | 否 | 完全关闭（**同时置全部任务转人工**，不是放行） |
| `shadow` | 执行并记录 | 执行并记录 | **否**（只落台账） | 灰度观测误杀，跑 3~7 天 |
| `enforce` | 执行 | 执行 | 是（回调 port） | 正式拦截 |

`shadow` 期间 `final_decision` 照常写，但**不调用 `applyDecision`**。切换成 `enforce` 后，历史 shadow 任务可选择性重投（`source=4 巡检重扫`）。

---

## 5. 数据库设计

### 5.1 数据纪律（三条，与现有表一致）

1. **审核表不存内容正文**：与 `ops_audit_log.detail_json`、`chat_context.content_*` 同一条纪律。审核表访问面更宽（运营队列/导出/排障），存正文等于给隐私数据造第二份副本。
2. **结论只追加**：改判写新的 `content_moderation_action` 行，不 UPDATE 旧结论；任务行只更新"执行状态"类列。
3. **删除一致性**：`is_deleted=1` 必须同时写 `deleted_at`（DDL 的 `chk_*_del_consistent` 会拒绝只置标志位的写法，这是 V1 踩过的坑）。

### 5.2 `content_moderation_task`（主表 = DB 队列）

```sql
CREATE TABLE content_moderation_task (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  content_type      TINYINT UNSIGNED NOT NULL COMMENT '1文章 2评论 3昵称 4头像 5个人简介 6私信群聊',
  target_id         BIGINT UNSIGNED NOT NULL COMMENT '业务主键（多态引用，不建外键）',
  author_id         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '内容作者，用于频控与信誉',
  source            TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1用户提交 2编辑重审 3举报重审 4巡检重扫 5申诉',
  content_hash      CHAR(64)      NOT NULL COMMENT '归一化内容 sha256：幂等与去重',
  content_len       INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '正文字符数（只记长度，不存内容）',
  media_count       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '图片数量',
  audit_status      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待执行 1执行中 2已出结论 3待人工 4失败 5申诉中',
  final_decision    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0未定 1机器通过 2机器拒绝 3人工通过 4人工驳回 5申诉改判通过',
  risk_level        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0无 1低 2中 3高（取最高命中）',
  decision_reason   VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '原因码（固定枚举，非自由文本）',
  rule_hit_count    SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '规则命中条数',
  model_hit_count   SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '模型命中条数',
  model_code        VARCHAR(64)   NULL COMMENT '本轮模型编码（复现用）',
  prompt_ver        VARCHAR(32)   NULL COMMENT '提示词版本标签如 moderation@v3（复现用）',
  prompt_tokens     INT UNSIGNED  NULL COMMENT '真实 prompt_tokens（成本统计）',
  completion_tokens INT UNSIGNED  NULL COMMENT '真实 completion_tokens',
  cost_ms           INT UNSIGNED  NULL COMMENT '本任务总耗时',
  attempts          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已尝试次数',
  next_retry_at     DATETIME(3)   NULL COMMENT '下次可执行时间（退避）',
  locked_by         VARCHAR(64)   NULL COMMENT '抢占者 实例:线程',
  locked_at         DATETIME(3)   NULL,
  created_by        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by        BIGINT UNSIGNED NULL,
  deleted_at        DATETIME(3)   NULL,
  is_deleted        TINYINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_cmt PRIMARY KEY (id),
  UNIQUE KEY uk_cmt_dedup (content_type, target_id, content_hash, source, is_deleted),
  KEY idx_cmt_queue (audit_status, next_retry_at),
  KEY idx_cmt_target (content_type, target_id),
  KEY idx_cmt_author_time (author_id, gmt_create),
  CONSTRAINT chk_cmt_type     CHECK (content_type BETWEEN 1 AND 6),
  CONSTRAINT chk_cmt_source   CHECK (source BETWEEN 1 AND 5),
  CONSTRAINT chk_cmt_status   CHECK (audit_status BETWEEN 0 AND 5),
  CONSTRAINT chk_cmt_decision CHECK (final_decision BETWEEN 0 AND 5),
  CONSTRAINT chk_cmt_risk     CHECK (risk_level BETWEEN 0 AND 3),
  CONSTRAINT chk_cmt_del_consistent CHECK (
      (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
      OR (is_deleted = 1 AND deleted_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='内容审核任务（DB 队列；只记元数据，不存正文）';
```

### 5.3 `content_moderation_hit`（命中证据，只追加）

```sql
CREATE TABLE content_moderation_hit (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id       BIGINT UNSIGNED NOT NULL COMMENT '所属任务',
  hit_source    TINYINT UNSIGNED NOT NULL COMMENT '1规则 2文本模型 3视觉模型',
  category_code VARCHAR(32)   NOT NULL COMMENT '固定枚举见 §5.6',
  severity      TINYINT UNSIGNED NOT NULL COMMENT '1低 2中 3高',
  confidence    TINYINT UNSIGNED NULL COMMENT '模型置信度0~100；规则命中为NULL',
  start_offset  INT UNSIGNED  NULL COMMENT '命中起始字符位置（原文，1基）',
  end_offset    INT UNSIGNED  NULL,
  snippet_hash  CHAR(64)      NULL COMMENT '命中片段 sha256（不存原文）',
  snippet_len   INT UNSIGNED  NULL COMMENT '命中片段字符数',
  segment_no    SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '长文分段序号，0=整篇',
  detail_json   VARCHAR(1000) NULL COMMENT '可解释理由（禁止写入原文）',
  created_by    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_cmh PRIMARY KEY (id),
  KEY idx_cmh_task (task_id, severity),
  CONSTRAINT chk_cmh_source CHECK (hit_source BETWEEN 1 AND 3),
  CONSTRAINT chk_cmh_severity CHECK (severity BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='审核命中明细（证据；只追加，不存原文）';
```

**offset 怎么来**：
- 规则层：正则/关键词直接命中原文 → offset 天然有效；
- 模型层：要求模型回吐命中**原句**，服务端用 `indexOf` 反查定位；查不到（模型改写/截断）→ `offset` 留 NULL，只存 `snippet_hash`。
- 前端高亮：只在人工复核页按 offset 高亮（该页面另有权限与留痕，见 §10.3）。

### 5.4 `content_moderation_action`（处置留痕，只追加）

```sql
CREATE TABLE content_moderation_action (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id     BIGINT UNSIGNED NOT NULL,
  action      TINYINT UNSIGNED NOT NULL COMMENT '1规则拒绝 2模型通过 3模型拒绝 4转人工 5人工通过 6人工驳回 7申诉改判 8重试 9回写失败 10转人工超时',
  from_status TINYINT UNSIGNED NULL COMMENT '变更前 audit_status',
  to_status   TINYINT UNSIGNED NULL COMMENT '变更后 audit_status',
  reason_code VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '原因码（固定枚举）',
  note        VARCHAR(255)  NULL COMMENT '人工备注（禁止填写内容原文）',
  ops_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '人工动作的操作人；机器动作为0',
  engine_ver  VARCHAR(64)   NULL COMMENT '规则集版本或模型编码：解释"当时为什么这么判"',
  created_by  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_cma PRIMARY KEY (id),
  KEY idx_cma_task (task_id, gmt_create),
  CONSTRAINT chk_cma_action CHECK (action BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='审核处置留痕（只追加，不可修改）';
```

### 5.5 `content_moderation_rule`（硬规则，可配置）

```sql
CREATE TABLE content_moderation_rule (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  content_type   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=通用',
  rule_type      TINYINT UNSIGNED NOT NULL COMMENT '1关键词 2正则 3长度上限 4链接白名单 5重复率阈值',
  pattern        VARCHAR(500)  NOT NULL COMMENT '关键词/正则/阈值',
  category_code  VARCHAR(32)   NOT NULL,
  severity       TINYINT UNSIGNED NOT NULL DEFAULT 2,
  action         TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT '1直接拒绝 2转人工 3仅记录',
  priority       SMALLINT UNSIGNED NOT NULL DEFAULT 100 COMMENT '越小越先匹配',
  is_enabled     TINYINT UNSIGNED NOT NULL DEFAULT 1,
  remark         VARCHAR(255)  NULL,
  config_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁',
  created_by     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_create     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  gmt_modified   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_by     BIGINT UNSIGNED NULL, deleted_at DATETIME(3) NULL,
  is_deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_cmr PRIMARY KEY (id),
  KEY idx_cmr_enabled (is_enabled, content_type, priority),
  CONSTRAINT chk_cmr_type   CHECK (rule_type BETWEEN 1 AND 5),
  CONSTRAINT chk_cmr_action CHECK (action BETWEEN 1 AND 3),
  CONSTRAINT chk_cmr_del_consistent CHECK (
      (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
      OR (is_deleted = 1 AND deleted_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='内容审核硬规则（关键词/正则/阈值；后台可配）';
```

规则走"库表 + 快照 + TTL + refreshNow"，与 `ai_prompt_template` / `ai_scene_config` 完全同构（后台改完本实例立即生效，其它实例最多滞后 60s）。

### 5.6 `category_code` 固定枚举（模型只能从这里选）

| code | 中文 | 默认严重度 | 默认动作 | 备注 |
| --- | --- | --- | --- | --- |
| `porn` | 色情低俗 | 3 | 拒绝 | |
| `violence` | 暴力血腥 | 3 | 拒绝 | |
| `illegal_goods` | 违禁药品/器械/偏方售卖 | 3 | 拒绝 | 与 `medical_claim` 区分：这里是**卖** |
| **`medical_claim`** | **疗效承诺（包治/根治/替代药物）** | 3 | 拒绝 | 本产品最高频风险 |
| **`medical_advice`** | **停药换药/剂量指导/劝人就医外解决** | 3 | 拒绝 | 与 `MedicalGuardAdvisor` 对齐 |
| `scam_ad` | 诈骗/导流/硬广 | 2 | 转人工 | 联系方式、二维码、拉群 |
| `privacy` | 隐私泄露（手机号/身份证/住址/病友实名） | 3 | 拒绝 | |
| `hate_harass` | 辱骂/人身攻击/病耻歧视 | 2 | 转人工 | |
| `political` | 政治敏感 | 3 | 拒绝 | |
| `minor` | 涉未成年人 | 3 | 转人工 | 一律不自动放行 |
| `pseudo_science` | 伪科学/谣言（"苦瓜根治糖尿病"） | 2 | 转人工 | |
| `off_topic` | 灌水/无关 | 1 | 仅记录 | 不拦截，只统计 |

**解析器规则**：出现不在本表中的 `code` → 该条丢弃；若因此没有任何有效命中且模型给了 `reject` → 视为解析失败 → **转人工**（不放行）。

### 5.7 `decision_reason` 原因码（固定枚举）

`rule_deny`（规则拒绝）、`rule_manual`（规则转人工）、`model_high_risk`（模型高风险）、`model_low_confidence`（置信度不足）、`parse_failed`（解析失败）、`call_failed`（调用失败）、`manual_pass`、`manual_reject`、`sample_check`（抽检）、`appeal_overturn`（二期）。

---

## 6. AI 侧模块结构

```text
ai/moderation/
├── controller/   AdminContentModerationController          管理端：队列/详情/处置/规则/指标
├── dto/          ContentModerationDtos
├── entity/       ContentModerationTaskEntity / HitEntity / ActionEntity / RuleEntity
├── mapper/       ContentModerationTaskMapper / HitMapper / ActionMapper / RuleMapper
├── service/      ContentModerationService（接口）
│                 ModerationRuleEngine（接口，纯函数）
├── service/impl/ ContentModerationServiceImpl / ModerationRuleEngineImpl
├── support/      ModerationPromptParser      模型输出解析 + 类别白名单
│                 ModerationContentNormalizer 归一化 / hash / 截断 / 分段
│                 ModerationTaskDispatcher    抢占 / 退避 / 启动补偿 / 定时补偿
├── config/       ModerationConfig            executor / 开关 / 阈值 / 策略
└── port/         ContentModerationPort       （AI 定义，社区模块实现）
```

### 6.1 核心接口

```java
public interface ContentModerationService {
    /** 提交审核（幂等）：同步跑规则层，返回 taskId 与当前状态。 */
    ContentModerationDtos.TaskView submit(ContentModerationDtos.SubmitRequest request);

    /** 管理端队列：按状态/风险/类型/时间分页（pageSize 缺省 20，上限 100，越界夹紧）。 */
    PageResult<ContentModerationDtos.TaskRow> page(ContentModerationDtos.Query query);

    /** 管理端详情：任务 + 命中明细元数据 + 处置历史（不含内容原文）。 */
    ContentModerationDtos.TaskDetail detail(Long taskId);

    /** 人工处置：PASS / REJECT，必须带 reasonCode；写 action 并回调端口。 */
    void decide(Long taskId, ContentModerationDtos.DecideRequest request);

    /** 指标：通过/拒绝/转人工率、时延分位、token 成本（按天聚合）。 */
    ContentModerationDtos.MetricsView metrics(LocalDate from, LocalDate to);
}
```

```java
/** 社区模块实现；必须幂等（AI 侧可能因回调失败重投）。 */
public interface ContentModerationPort {
    void applyDecision(ContentModerationDtos.DecisionCallback callback);
}
```

### 6.2 可直接复用的现成件

| 能力 | 复用对象 | 位置 |
| --- | --- | --- |
| 提示词后台可改/可回滚/启动校验 | `AiPromptTemplateService` | `ai/prompt/service/AiPromptTemplateService.java` |
| 每场景绑更便宜模型 + 温度 | `AiSceneBindingService` + `ai_scene_config` | `ai/scene/service/` |
| 请求级参数（关思维链 + 封顶） | `SceneModelResolver` 新增 `moderationOptionsBuilder()` | `ai/core/SceneModelResolver.java` |
| 新场景注册 | `SceneCode` 增 `MODERATION` / `MODERATION_IMAGE` | `ai/core/SceneCode.java` |
| 图片审核 | `VisionAnalyzer.analyze(media, MODERATION_IMAGE, instruction)` | `ai/vision/VisionAnalyzer.java` |
| 异步线程池 | 仿 `OcrTaskConfig.ocrTaskExecutor` | `ai/business/ocr/config/OcrTaskConfig.java` |
| 幂等键 | `IdempotencyStore` | `common/idempotency/IdempotencyStore.java` |
| 运营动作留痕 + 查询页 | `OpsAuditRecorder` + `/admin/v1/ops-audit-logs` | `common/audit/` |
| 只追加实体 | `AppendOnlyEntity` | `common/model/AppendOnlyEntity.java` |
| 单飞（同一任务不并发） | `ChatRoundGuard`（key=`moderate:<taskId>`） | `ai/support/ChatRoundGuard.java` |
| 统一响应/分页/错误码 | `ApiResponse` / `PageResult` / `ErrorCode` | `common/api/` |
| 迁移写法（幂等 DDL、BINARY 比较 scene_code） | 照 V26~V28 | `db/migration/` |

---

## 7. 对外契约

### 7.1 社区模块 → AI（内部服务调用，非 HTTP）

| 方法 | 入参 | 出参 |
| --- | --- | --- |
| `submit` | `contentType, targetId, authorId, source, title?, text, mediaIds[]` | `taskId, auditStatus, finalDecision, reasonCode` |
| `page/detail/decide/metrics` | 管理端使用 | — |

`submit` 的**同步返回值**要能让前端立刻渲染：
- `auditStatus=0` → "审核中"
- `finalDecision=1` → 直接可见
- `finalDecision=2` → 提示"内容不符合社区规范"
- `auditStatus=3` → "已提交人工审核，预计 X 分钟内完成"

### 7.2 AI → 社区模块（端口回调）

```java
public record DecisionCallback(
        Long taskId, int contentType, Long targetId,
        int auditStatus, int finalDecision, int riskLevel,
        String reasonCode, String topCategory) { }
```

社区侧实现要求：**幂等**（同一 `taskId` 重复回调结果一致）；不抛异常给 AI（AI 只 WARN）。

### 7.3 管理端接口（本期新增，契约 4.20~4.24 待补进 `接口设计文档.md`）

| 编号 | 方法与路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| 4.20 | `GET /admin/v1/content-moderation-tasks` | `content:moderation:view` | 队列：`contentType/auditStatus/riskLevel/targetId/authorId/from/to/pageNum/pageSize` |
| 4.21 | `GET /admin/v1/content-moderation-tasks/{id}` | `content:moderation:view` | 详情：任务 + 命中元数据 + 处置历史（**不含原文**） |
| 4.22 | `POST /admin/v1/content-moderation-tasks/{id}/decisions` | `content:moderation:decide` | 人工处置：`decision=PASS/REJECT, reasonCode, note` |
| 4.23 | `GET/POST/PATCH /admin/v1/content-moderation-rules[/{id}]` | `content:moderation:rule:manage` | 规则列表/新增/编辑（带 `configVersion` 乐观锁） |
| 4.24 | `GET /admin/v1/content-moderation-metrics?from=&to=` | `content:moderation:view` | 指标：通过率/转人工率/时延分位/token 成本/命中类别分布 |

出参形状与现有管理端一致：`data = { records, total }`（分页只给这两个字段，pageSize 夹紧 20/100）。

### 7.4 小程序端（本期不新增 AI 接口）

用户侧只看到社区模块转述的"审核中/未通过"，不直连 AI。二期申诉才有 `POST /api/v1/community/appeals`（社区侧实现，转 `source=5` 的新任务）。

---

## 8. 提示词设计

### 8.1 文本审核提示词（`prompts/moderation/text.md`）

硬约束：**禁止出现 JSON 字面量、成对花括号、方括号**——仓库 V22/V23/V27 三次因此启动失败（ST 模板会把它们当变量表达式）。描述输出格式一律用**字段清单**。

```text
# 角色：内容审核器
你是控糖社区的内容审核器。你不生成内容、不回答用户、不给医学建议，只做判定。

## 判定类别（只能从下列 code 中选，禁止自创）
porn 色情低俗 / violence 暴力血腥 / illegal_goods 违禁药品器械售卖 /
medical_claim 疗效承诺（"包治""根治""一周降 5 个点"）/
medical_advice 停药换药、剂量指导、劝人不要就医 /
scam_ad 导流诈骗硬广 / privacy 隐私泄露（手机号、身份证、住址、病友实名）/
hate_harass 辱骂与病耻歧视 / political 政治敏感 / minor 涉未成年人 /
pseudo_science 伪科学偏方 / off_topic 灌水无关

## 输出格式
逐行输出，每行一个命中，字段用中文冒号分隔：
verdict：[pass|reject|manual]
risk：[0|1|2|3]
code：上述 code 之一
severity：[1|2|3]
confidence：0 到 100 的整数
evidence_quote：命中的原句，不超过 40 字，找不到原句就留空
segment：分段序号，整篇为 0

没有任何命中时只输出一行：verdict：pass
拿不准一律输出 verdict：manual，禁止为了省事输出 pass。

## 判据
1. 只判内容本身，不推测作者动机；
2. 分享个人控糖经历、引用权威指南不算 medical_claim；只有普适化承诺才算；
3. 讨论药物原理可以；给出具体剂量、加减药方案判 medical_advice；
4. 礼貌的反对意见不算 hate_harass；
5. 涉及未成年人的任何内容，即使是善意科普也输出 manual。
```

### 8.2 图片审核提示词（`prompts/moderation/image.md`）

复用 `VisionAnalyzer.analyze(media, SceneCode.MODERATION_IMAGE, instruction)`，输出同上（`code` 取 `porn/violence/illegal_goods/scam_ad/privacy/minor`）。`evidence_quote` 留给图片内容描述（**不超过 20 字，不带可识别信息**）。

### 8.3 解析器（`ModerationPromptParser`）

| 情况 | 处理 |
| --- | --- |
| 输出为空 / 只有空白 | `manual` + `parse_failed` |
| `verdict` 不在枚举内 | `manual` + `parse_failed` |
| `code` 不在 §5.6 表内 | 丢弃该条；若剔除后无有效命中且 verdict=reject → `manual` |
| `confidence` 缺失 | 按 0 处理（不会触发自动拒绝） |
| `evidence_quote` 在原文中找不到 | `offset=NULL`，只存 hash |
| 输出超过 2000 字 | 截断后解析，并记 WARN |

### 8.4 配置（`application.yaml`）

```yaml
ai:
  scenes:
    moderation:
      prompt: prompts/moderation/text.md
      version: v1
      temperature: 0          # 判定任务要稳定
    moderation-image:
      prompt: prompts/moderation/image.md
      version: v1
      temperature: 0
  moderation:
    enabled: true
    mode: shadow              # off | shadow | enforce
    max-chars-per-audit: 3000 # 单次送模型的字符上限
    segment-overlap: 200      # 分段重叠，防跨段违规漏判
    max-segments: 8           # 分段上限（成本闸门）
    manual-risk-threshold: 2  # risk >= 2 转人工
    auto-reject-confidence: 85 # confidence >= 85 且 risk=3 才自动拒绝
    retry-max: 3
    poll-interval-seconds: 30 # 补偿轮询
    batch-size: 50
```

`auto-reject-confidence` 默认 85：**宁可多转人工，不可错杀**。灰度期可按 shadow 数据调到 90~95。

---

## 9. 成本 / 时延 / 容量

| 手段 | 具体做法 | 预期收益 |
| --- | --- | --- |
| L1 规则前置 | 关键词/正则先跑，命中即出结论 | 拦掉大部分显性违规，0 token |
| 截断 | 文章只审 `title + 摘要 + 前 3000 字` | 长文成本封顶 |
| 分段重叠 | 段间 200 字重叠，`max-segments=8` | 防漏判 + 成本上限 |
| 去重 | `content_hash` 相同复用最近结论（含来源任务号） | 转载/复制粘贴的评论成本≈0 |
| 频控 | `author_id` 维度（如 1 分钟 20 条）超限降级为转人工 | 防刷屏刷成本 |
| 模型档位 | `ai_scene_config` 给 `MODERATION` 绑最便宜的模型 + 关思维链 + `maxTokens=300` | 分类任务不需要推理大模型 |
| 异步 | 提交即返回，审核在后台 | 用户无等待感（评论场景必需） |
| 降级 | 模型不可用 → 转人工 + 退避重试 | **不阻塞也不放行** |

时延目标（SLO）：评论 P95 < 20s、文章 P95 < 90s（含图片）、人工队列响应 < 4h（运营承诺）。

---

## 10. 合规与红线

### 10.1 五条硬红线（写进实现注释，评审时逐条对）

1. **失败不放行**：异常/超时/解析失败/类别越界 → `manual`，永不 `pass`。
2. **证据只存元数据**：hit 表只有 offset + `snippet_hash`，不存原句。
3. **机器不处罚账号**：只给内容级结论与 `risk_level`；禁言/封禁必须人工，且写 `ops_audit_log`。
4. **结论不可变**：改判 = 追加 `action` 行；申诉 = 新任务（`source=5`），原结论保留。
5. **可解释**：任何拒绝都能给出 `category_code + 最高命中 + prompt_ver + model_code`。

### 10.2 医疗类专有条文

命中 `medical_claim` / `medical_advice` 的拒绝文案必须包含就医引导（复用 `MedicalGuardAdvisor` 的固定话术风格），而不是干巴巴"内容违规"。病历类细节（具体用药方案）**不进入人审备注**，只记 `category_code`。

### 10.3 人工复核的隐私控制

- 详情接口（4.21）**不返回原文**，只返回命中元数据；
- 人工要看原文时走社区侧的"受控查看"接口：需 `content:moderation:decide` + 每次调用写 `ops_audit_log`（`ai_moderation_content_view`）；
- 私信/群聊（`content_type=6`）：默认**盲审**——只看命中片段 ±30 字上下文，不展示整段会话；用户举报成立且升级为高危时才允许全文，且必须留痕。

### 10.4 私信/群聊的额外约束（若一期上线私信）

若消息正文已端到端加密或按会话密钥加密，AI 侧拿不到明文 → 必须先定：① 服务端持明文（内部审核可读）；② 或仅在"被举报"时解密送审。**这一点必须由产品与法务先定，未定之前 `content_type=6` 不得开启**。

### 10.5 数据保留

- 任务/命中/动作表保留 **6 个月**，之后归档或物理清理（保留 `action` 的计数摘要）；
- 指标按天聚合长期保留（不含内容）。

---

## 11. 迁移与发布

### 11.1 `V29__content_moderation.sql`

1. 建 4 张表（§5.2~5.5）+ 全部 CHECK 与索引；
2. `sys_menu`：审核队列页（2040）、规则管理页（2041）、指标页（2042）——取 2040+ 空段，避开已用的 2013/2018/2020-2025/2030-2032；
3. 权限点：`content:moderation:view` / `content:moderation:decide` / `content:moderation:rule:manage`；
4. `sys_role_menu`：授予 `super_admin` / `operator`；
5. 播种 `moderation` 与 `moderation-image` 两个场景提示词 v1（**启用**，字段清单式正文；BINARY 比较 `scene_code`）；
6. 播种基础规则 12~20 条（联系方式正则、二维码/拉群话术、医疗断言关键词：包治/根治/停药/加量/偏方/降糖神药）。

### 11.2 发布顺序

```text
1) 后端 V29 上线，ai.moderation.enabled=false（接口可用但全转人工，不拦截）
2) 管理端上线审核队列页 + 规则页
3) 社区侧实现 ContentModerationPort 并接入 submit
4) ai.moderation.enabled=true 且 mode=shadow，跑 3~7 天看误杀与命中分布
5) mode=enforce 正式拦截；保留 shadow 报告页便于回归
```

### 11.3 回滚

`mode=off` 立即停止拦截（审核照常记录）；`enabled=false` 时所有任务直接进人工队列（**不是放行**），保证"关掉 AI 也不失控"。

---

## 12. 测试计划

| 层次 | 类 | 用例数（估） | 必测点 |
| --- | --- | --- | --- |
| 规则引擎（纯函数） | `ModerationRuleEngineTest` | 15 | 关键词/正则/优先级/白名单/长度/大小写与全半角/重复率 |
| 解析器（纯函数） | `ModerationPromptParserTest` | 12 | 合法输出、越界 code、空输出、非法 verdict、缺 confidence、超长、找不到原句 |
| 服务与状态机 | `ContentModerationServiceTest` | 15 | 幂等 submit、抢占不重复、退避重试、失败转人工、人审追加 action、端口失败补偿 |
| 真库（H2） | `ContentModerationRepositoryTest` | 5 | 唯一键幂等、删除一致性 CHECK、只追加表无 update、条件抢占成功/失败 |
| 端到端 | `scripts/e2e/moderation-e2e.ps1` | ~20 | 提交→轮询→结论；规则直拒；模型失败→人工；人审放行/驳回；shadow 不拦截；`mode=off` 全转人工 |
| 指标 | 每天抽样 | — | 随机抽 20 条机器 PASS 的人工复看，统计**误杀率** |

验收门槛（切换 `enforce` 前）：
- 误杀率 ≤ 2%（人工抽样）；
- 高危漏放 = 0（抽样中不得出现应拒未拒）；
- 评论 P95 时延 < 20s；规则层命中率 ≥ 60%（低于则说明规则太薄，成本会失控）。

---

## 13. 分期计划

| 期 | 范围 | 对应文件 |
| --- | --- | --- |
| **P0** | V29 迁移（4 表 + 菜单 + 权限 + 提示词 + 规则种子）、规则引擎、任务队列（线程池 + 补偿轮询 + 启动补偿）、文本模型审核、解析器、人工处置接口、端口回调、影子模式、单测 47 个、`moderation-e2e.ps1` | `ai/moderation/**`、`V29`、`prompts/moderation/*` |
| **P1** | 长文分段与重叠、去重缓存、频控、指标接口与页面、图片审核（`VisionAnalyzer` + `MODERATION_IMAGE`）、管理端详情页命中高亮 | 同上 + 管理端 `views/community/ModerationView.vue` |
| **P2** | 申诉（`source=5` + 改判 + 用户入口）、信誉分（`user_moderation_profile`）、巡检重扫、误杀抽样工具、私信/群聊审核（若私信上线） | 新增表 + 社区侧联动 |

---

## 14. 待确认与风险

| # | 问题 | 影响 | 建议 |
| --- | --- | --- | --- |
| R1 | **私信/群聊是否与社区同期上线** | 若同期，`content_type=6` 必须 P0 就做；且需先定 §10.4 的明文可见性 | 建议私信独立一期；若同期则必须同期审核 |
| R2 | 社区侧 `ContentModerationPort` 由谁实现、何时联调 | 阻塞闭环验收 | 建议社区方在 P0 内提供空实现，先跑通 shadow |
| R3 | 申诉是否二期（已定 D5=二期） | 二期需要任务新状态 `5 申诉中` 与 `source=5`，本设计已预留 | 无需动作 |
| R4 | **定时调度未开启**（`ScheduleConfig` 被注释） | 补偿轮询无处挂载 | 需在 P0 决定：开 `@EnableScheduling` 或挂 ruoyi-quartz |
| R5 | 图片审核是否复用 `ai.vision.model`（当前 qwen-vl） | 视觉审核与视觉识别共用通道，成本叠加 | 建议给 `MODERATION_IMAGE` 在 `ai_scene_config` 里单独绑更便宜的视觉模型 |
| R6 | `media_file.biz_type` 是否需要新增社区图片类型 | 归属校验依赖 bizType | 建议新增 `COMMUNITY_IMAGE(6)`（社区侧迁移） |
| R7 | 私信/群聊是否需要留档（法务） | 影响保留期与是否允许机器读取 | 需产品 + 法务确认 |

---

## 15. 附录

### 15.1 术语

- **L1/L2/L3**：规则层 / 模型层 / 人工层；
- **shadow**：影子模式，只记录不拦截；
- **误杀**：机器判拒绝但人工判定应通过；
- **漏放**：机器判通过但人工判定应拒绝。

### 15.2 与现有模块的对照

| 现有能力 | 对应本设计 |
| --- | --- |
| `MedicalGuardAdvisor`（代码层硬拦聊天） | L1 规则的同类；本设计把它扩展到 UGC |
| `FORBIDDEN_MARKERS`（长期记忆拦截剂量/诊断词） | 规则种子可复用这批关键词 |
| `ai_prompt_template` 版本化 + 回滚 | 审核提示词同机制 |
| `ai_scene_config` 场景绑模型 | `MODERATION` 绑最便宜模型 |
| `ops_audit_log` 只追加 + 查询页 | `content_moderation_action` 同思路；人审动作再写一条 ops 审计 |
| `chat_context` 只存元数据 | `content_moderation_hit` 同纪律 |
| `OcrTaskService` 任务表 + 线程池 | 本设计的队列与消费者 |
| `device/port/GlucoseIngestPort` | `ContentModerationPort` |
