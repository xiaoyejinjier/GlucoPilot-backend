-- 场景配置（场景 → 模型 与 场景级参数）：把 yaml ai.scenes.<scene>.* 里的"绑哪个模型、用什么参数"
-- 搬到后台，运营改完不用重新打包。
--
-- 现状（迁移前）：SceneModelResolver 读 yaml 的 ai.scenes.<scene>.model-code / temperature /
-- max-tokens / thinking，与代码里的 SceneCode 一一对应；DB 侧完全没有这张表。
--
-- 生效范围（刻意写清楚，避免"改了没反应"这种最难查的问题）：
--   * model_code：**仅文本场景生效**（CHAT）。视觉场景的模型由视觉通道统一解析
--     （ai_model_config 里 provider=2 的那一行，或 ai.vision.model 兜底），逐场景选模型尚未支持
--     —— 视觉场景填了会记录 + 打 WARN，属 P2。
--   * temperature / max_tokens：文本场景走请求级 DeepSeekChatOptions；视觉场景走请求级
--     OpenAiChatOptions（VisionAnalyzer 注入），两者都生效。
--   * thinking：DeepSeek 专有，仅文本场景有意义。
--
-- 种子 = **迁移前实际生效的值**（不是 yaml 里声明的值），保证上线行为零变化。
-- 这里有两处历史遗留：yaml 的 ai.scenes.meal-analysis.temperature=0.2 与
-- ai.scenes.ocr-glucose.temperature=0 对**视觉场景从未生效**（当时视觉参数只有 ai.vision.* 与
-- VisionAnalyzer 里写死的 0.1 会被用上）。因此：
--   * MEAL_ANALYSIS 取 0.10（= 代码里写死的值），不是 yaml 的 0.2；
--   * OCR_GLUCOSE 留 NULL（= 继承通道默认 0.2），需要"稳定不发挥"时在后台一键改成 0 即可。
-- 现在这些值都真正生效了，改哪个场景的哪个参数都能立刻验证。
--
-- 【版本号说明】本迁移最初编为 V17，与远端并行的"血糖预警中心"迁移撞号，已重编为 V19；
-- 建表用 IF NOT EXISTS，使"已按旧号应用过"的环境（本机开发库）重放时是空操作。

CREATE TABLE IF NOT EXISTS ai_scene_config (
  id             BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  scene_code     VARCHAR(64)      NOT NULL COMMENT '场景编码，见 SceneCode.getCode()',
  model_code     VARCHAR(64)      NULL COMMENT '绑定的模型编码（ai_model_config.model_code）；NULL=用通道默认',
  temperature    DECIMAL(3,2)     NULL COMMENT '场景级温度；NULL=继承默认',
  max_tokens     INT UNSIGNED     NULL COMMENT '场景级输出上限；NULL=继承默认',
  thinking       TINYINT(1)       NULL COMMENT '是否开启推理链（DeepSeek 专有）；NULL=不显式设置',
  is_enabled     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用 0否1是',
  config_version BIGINT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '乐观锁版本，任何写操作 +1',
  remark         VARCHAR(255)     NULL COMMENT '备注',
  created_by     BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by     BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by     BIGINT UNSIGNED  NULL COMMENT '删除人',
  deleted_at     DATETIME(3)      NULL COMMENT '删除时间',
  is_deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_scene_code (scene_code, is_deleted),
  KEY idx_scene_enabled (is_enabled),
  -- CHECK 约束名在 MySQL 里是**整个 schema 唯一**的（不是每表唯一），
  -- 因此前缀必须与既有表错开：chk_asc_* 已被 auth_sms_code 占用（踩过一次：
  -- CREATE TABLE 报 "Duplicate check constraint name 'chk_asc_del'"，整个 V19 迁移失败）。
  CONSTRAINT chk_ascfg_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_ascfg_temp CHECK (temperature IS NULL OR (temperature >= 0 AND temperature <= 2)),
  CONSTRAINT chk_ascfg_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_ascfg_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 场景配置（场景→模型与场景级参数）';

-- 种子：与迁移前实际生效的值一致（CHAT 全继承；视觉场景沿用 ai.vision.* 的温度/上限）
INSERT INTO ai_scene_config (scene_code, model_code, temperature, max_tokens, thinking, is_enabled, remark)
SELECT 'CHAT', NULL, NULL, NULL, NULL, 1, 'V19：与 yaml ai.scenes.chat 一致（全部继承通道默认）'
 WHERE NOT EXISTS (SELECT 1 FROM ai_scene_config WHERE scene_code = 'CHAT' AND is_deleted = 0);

INSERT INTO ai_scene_config (scene_code, model_code, temperature, max_tokens, thinking, is_enabled, remark)
SELECT 'CHAT_IMAGE', NULL, 0.20, 512, NULL, 1, 'V19：与 ai.vision.temperature/max-tokens 一致（模型仍由视觉通道解析）'
 WHERE NOT EXISTS (SELECT 1 FROM ai_scene_config WHERE scene_code = 'CHAT_IMAGE' AND is_deleted = 0);

INSERT INTO ai_scene_config (scene_code, model_code, temperature, max_tokens, thinking, is_enabled, remark)
SELECT 'MEAL_ANALYSIS', NULL, 0.10, NULL, NULL, 1, 'V19：0.10 = 迁移前 VisionAnalyzer 里写死的温度；输出上限继承 ai.meal.max-tokens'
 WHERE NOT EXISTS (SELECT 1 FROM ai_scene_config WHERE scene_code = 'MEAL_ANALYSIS' AND is_deleted = 0);

INSERT INTO ai_scene_config (scene_code, model_code, temperature, max_tokens, thinking, is_enabled, remark)
SELECT 'OCR_GLUCOSE', NULL, NULL, NULL, NULL, 1, 'V19：温度继承通道默认(0.2)；需要"稳定不发挥"可在此改成 0'
 WHERE NOT EXISTS (SELECT 1 FROM ai_scene_config WHERE scene_code = 'OCR_GLUCOSE' AND is_deleted = 0);