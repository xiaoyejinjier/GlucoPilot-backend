-- =====================================================================================
-- V75：模型调用账本 ai_call_log
--
-- 为什么需要它：现有三张表各自只有"半张账单"——
--   · chat_message     = 消息属性（一条助手回复一行；工具回环里只留最后一次 usage，且只覆盖 CHAT）
--   · chat_tool_call   = 工具执行元数据（不是模型调用）
--   · ops_audit_log    = 人操作的审计（detail_json ≤2000，按 ops_id 索引，写机器调量级与语义都不对）
-- 摘要、记忆抽取、内容审核、翻译、视觉识别都走 utility/vision 客户端、不落任何表，
-- 于是"哪个场景花了多少钱"完全答不出来。本表是**唯一账单口径**，与上面三者禁止相加。
--
-- 只追加、无逻辑删除：90 天保留期由 AiCallLogCleanupJob 分批物理删除。
-- 不存任何正文：只存元数据（场景/模型/用量/耗时/成败），脱敏面尽量小。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS ai_call_log
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scene_code        VARCHAR(32)     NOT NULL DEFAULT '' COMMENT 'SceneCode 枚举名（CHAT/SUMMARY/MEMORY/…，不用 yaml 小写键）',
    channel           VARCHAR(16)     NULL COMMENT 'text / vision：同一场景号可能走不同通道，排障时用来对齐设备',
    model_code        VARCHAR(64)     NULL COMMENT '模型身份（ai_model_config.model_code），不是 endpoint',
    model_endpoint    VARCHAR(128)    NULL COMMENT '实际发给厂商的模型名（与 model_code 分开：前者可改、后者不可改）',
    user_id           BIGINT UNSIGNED NULL COMMENT '用户 ID；系统调用（审核节拍器/翻译）为空',
    session_id        BIGINT UNSIGNED NULL COMMENT '会话 ID；非对话场景为空',
    prompt_tokens     INT UNSIGNED    NULL COMMENT '提示 token（取不到存 NULL，不存 0）',
    completion_tokens INT UNSIGNED    NULL COMMENT '生成 token（同上）',
    cache_hit_tokens  INT UNSIGNED    NULL COMMENT '上下文缓存命中 token（同上）',
    cache_miss_tokens INT UNSIGNED    NULL COMMENT '未命中 token（= prompt − hit）',
    latency_ms        INT UNSIGNED    NULL COMMENT '耗时毫秒（成功与失败都记）',
    stream            TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1=流式（SSE），0=同步',
    ok                TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 成功、0 失败',
    error_code        VARCHAR(128)    NULL COMMENT '失败时的异常类名（不记消息正文，避免把用户内容写进账本）',
    gmt_create        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '调用发生时间',
    CONSTRAINT pk_acl_id PRIMARY KEY (id),
    KEY idx_acl_scene_time (scene_code, gmt_create),
    KEY idx_acl_model_time (model_code, gmt_create),
    KEY idx_acl_user_time (user_id, gmt_create),
    CONSTRAINT chk_acl_ok CHECK (ok IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='模型调用账本（只追加，无逻辑删除；90 天保留）';
