-- 适食规则表：餐食适食分析的判定规则（阶段 1a 骨架，仅验证计算通路）。
-- 判定顺序由 priority 升序决定：GI 覆盖(5) > 分类规则(10~30) > 兜底(不落表，引擎内置 UNKNOWN)。
-- 阈值/档位全部落表，代码不写死；本期由 DBA 维护，不提供管理端。
-- rule_version：本脚本仅灌 2026.11.0-placeholder 占位规则，正式 V1 规则在 1b 整体替换为 2026.11.1。

CREATE TABLE food_suitability_rule (
  id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  rule_code     VARCHAR(32)      NOT NULL COMMENT '规则编码：分类规则 CAT{n}_xxx / GI 规则 GI_xxx / 碳水规则 CARB_xxx',
  category_code TINYINT UNSIGNED NULL     COMMENT '食物大类 1-9，可空表示不限分类（见 food_category）',
  gi_min        DECIMAL(5,1)     NULL     COMMENT 'GI 下限（含），可空表示不限',
  gi_max        DECIMAL(5,1)     NULL     COMMENT 'GI 上限（含），可空表示不限',
  carbs_min     DECIMAL(8,1)     NULL     COMMENT '每100g碳水下限（含）g，可空表示不限；1a 未启用',
  carbs_max     DECIMAL(8,1)     NULL     COMMENT '每100g碳水上限（含）g，可空表示不限；1a 未启用',
  tier          VARCHAR(16)      NOT NULL COMMENT '判定档位：LOW|MODERATE|HIGH',
  priority      INT UNSIGNED     NOT NULL COMMENT '匹配优先级，升序优先；GI 覆盖 5 > 分类规则 10~30',
  rule_version  VARCHAR(32)      NOT NULL COMMENT '规则版本，如 2026.11.0-placeholder / 2026.11.1',
  is_enabled    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用 0否1是',
  created_by    BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by    BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified  DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  deleted_by    BIGINT UNSIGNED  NULL     COMMENT '删除人',
  deleted_at    DATETIME(3)      NULL     COMMENT '删除时间',
  is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_fsr_ver_code (rule_version, rule_code),
  KEY idx_ver_en_prio (rule_version, is_enabled, priority),
  KEY idx_ver_cat (rule_version, category_code),
  CONSTRAINT chk_fsr_code CHECK (rule_code <> ''),
  CONSTRAINT chk_fsr_tier CHECK (tier IN ('LOW', 'MODERATE', 'HIGH')),
  CONSTRAINT chk_fsr_cat CHECK (category_code IS NULL OR category_code BETWEEN 1 AND 9),
  CONSTRAINT chk_fsr_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_fsr_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_fsr_cond CHECK (
    category_code IS NOT NULL
    OR gi_min IS NOT NULL
    OR gi_max IS NOT NULL
    OR carbs_min IS NOT NULL
    OR carbs_max IS NOT NULL
  ),
  CONSTRAINT chk_fsr_del_consistent CHECK (
    (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL)
    OR (is_deleted = 1 AND deleted_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='适食规则表（餐食升糖负担判定）';

-- 占位种子：8 条，仅用于验证「GI 覆盖 → 分类规则 → 兜底」通路，内容不代表最终营养结论。
-- 5 奶类 / 7 坚果类故意不种，用于验证「信息不足 → UNKNOWN」分支。
-- Flyway 不走 MyBatis MetaObjectHandler，审计列必须显式赋值；
-- chk_fsr_del_consistent 要求 is_deleted=0 时 deleted_at/deleted_by 均为 NULL。
INSERT INTO food_suitability_rule
  (rule_code, category_code, gi_min, gi_max, carbs_min, carbs_max, tier, priority, rule_version, is_enabled,
   created_by, updated_by, gmt_create, gmt_modified, deleted_at)
VALUES
  ('GI_HIGH_70',          NULL, 70.0, NULL, NULL, NULL, 'HIGH',      5, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT9_SNACK_HIGH',     9,    NULL, NULL, NULL, NULL, 'HIGH',     10, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT8_DRINK_HIGH',     8,    NULL, NULL, NULL, NULL, 'HIGH',     10, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT3_VEG_LOW',        3,    NULL, NULL, NULL, NULL, 'LOW',      20, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT2_PROTEIN_LOW',    2,    NULL, NULL, NULL, NULL, 'LOW',      20, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT6_SOY_LOW',        6,    NULL, NULL, NULL, NULL, 'LOW',      20, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT1_STAPLE_MODERATE', 1,   NULL, NULL, NULL, NULL, 'MODERATE', 30, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL),
  ('CAT4_FRUIT_MODERATE', 4,    NULL, NULL, NULL, NULL, 'MODERATE', 30, '2026.11.0-placeholder', 1, 0, 0, NOW(), NOW(), NULL);
