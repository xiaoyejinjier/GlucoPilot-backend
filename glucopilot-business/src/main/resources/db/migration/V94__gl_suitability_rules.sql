-- =====================================================================
-- V94 适食判定切换为 per100gGL（每 100g 升糖负荷）主指标
-- 设计要点：
--   1) per100gGL = GI × 每100g碳水 / 100，食物固有属性，与份量无关；
--      份量只做离散量级修正（meal_portion_adjust_rule），不做精确乘算。
--   2) 阈值语义与 gi_min/gi_max 一致：gl_min/gl_max 均含边界。
--      三档区间在边界处重叠（10、20），由 priority 消解：
--      GL_MODERATE(10) < GL_LOW(20) < GL_HIGH(30)，
--      因此 per100gGL=10 或 20 均归 MODERATE（口径：10≤x≤20 → MODERATE，>20 → HIGH）。
--   3) 旧版本 2026.11.0-placeholder 整体 is_enabled=0，不原地改语义
--      （符合 V88 注释「1b 整体替换」约定）；新版本 2026.11.1 全新插入。
--   4) 旧规则 carbs_min/carbs_max 全为 NULL，无语义明确的 CARB 规则可迁，
--      不存在「被短路」的中间态；metric/scope 对旧行保持 NULL（引擎按 GI/ITEM 兼容解释）。
--   5) DDL 由 Flyway 版本机制保证单次执行；DML 用 uk_fsr_ver_code + ON DUPLICATE KEY 幂等。
-- =====================================================================

-- ---------- 1. 规则表扩展：metric / scope / gl 阈值 ----------
ALTER TABLE food_suitability_rule
  ADD COLUMN metric VARCHAR(8) NULL
    COMMENT '判定指标：GL=每100g升糖负荷|GI=升糖指数|CARB=每100g碳水；旧行为 NULL 时引擎按 GI 解释'
    AFTER rule_code,
  ADD COLUMN scope VARCHAR(8) NULL
    COMMENT '规则作用域：ITEM=单项|MEAL=整餐；旧行为 NULL 时引擎按 ITEM 解释'
    AFTER metric,
  ADD COLUMN gl_min DECIMAL(6,1) NULL
    COMMENT 'per100gGL 下限（含），可空表示不限；仅 metric=GL 时生效'
    AFTER gi_max,
  ADD COLUMN gl_max DECIMAL(6,1) NULL
    COMMENT 'per100gGL 上限（含），可空表示不限；仅 metric=GL 时生效'
    AFTER gl_min;

ALTER TABLE food_suitability_rule
  ADD CONSTRAINT chk_fsr_metric CHECK (metric IS NULL OR metric IN ('GL', 'GI', 'CARB')),
  ADD CONSTRAINT chk_fsr_scope  CHECK (scope IS NULL OR scope IN ('ITEM', 'MEAL'));

-- 旧 CHECK 只认 category/gi/carbs 五列，会拒绝纯 GL 规则；重建为包含 gl 列
ALTER TABLE food_suitability_rule DROP CHECK chk_fsr_cond;
ALTER TABLE food_suitability_rule
  ADD CONSTRAINT chk_fsr_cond CHECK (
    category_code IS NOT NULL
    OR gi_min IS NOT NULL
    OR gi_max IS NOT NULL
    OR carbs_min IS NOT NULL
    OR carbs_max IS NOT NULL
    OR gl_min IS NOT NULL
    OR gl_max IS NOT NULL
  );

-- ---------- 2. 旧版本整体下线 ----------
UPDATE food_suitability_rule
SET is_enabled = 0, updated_by = 0, gmt_modified = NOW(3)
WHERE rule_version = '2026.11.0-placeholder' AND is_enabled = 1;

-- ---------- 3. 新版本 2026.11.1：ITEM 阶段 per100gGL 三档 ----------
-- 不插入类别规则：库命中但缺 GI/碳水时按 UNKNOWN 降级并说明缺失字段，类别仅作展示。
INSERT INTO food_suitability_rule
  (rule_code, metric, scope, category_code, gl_min, gl_max, tier, advice_text,
   priority, rule_version, is_enabled, created_by, updated_by, gmt_create, gmt_modified, deleted_at)
VALUES
  ('GL_ITEM_MODERATE', 'GL', 'ITEM', NULL, 10.0, 20.0, 'MODERATE',
   '{foodName}每100g升糖负荷中等，建议控制份量',
   10, '2026.11.1', 1, 0, 0, NOW(), NOW(), NULL),
  ('GL_ITEM_LOW', 'GL', 'ITEM', NULL, NULL, 10.0, 'LOW',
   '{foodName}每100g升糖负荷低，可按常量食用',
   20, '2026.11.1', 1, 0, 0, NOW(), NOW(), NULL),
  ('GL_ITEM_HIGH', 'GL', 'ITEM', NULL, 20.0, NULL, 'HIGH',
   '{foodName}每100g升糖负荷偏高，建议少量或替换',
   30, '2026.11.1', 1, 0, 0, NOW(), NOW(), NULL)
ON DUPLICATE KEY UPDATE
  metric = VALUES(metric), scope = VALUES(scope), gl_min = VALUES(gl_min), gl_max = VALUES(gl_max),
  tier = VALUES(tier), advice_text = VALUES(advice_text), priority = VALUES(priority),
  is_enabled = VALUES(is_enabled), gmt_modified = NOW(3);

-- ---------- 4. 份量量级修正表（二维映射：基础档位 × 碳水量级桶 → 修正档位） ----------
-- 桶边界：carb_bucket_min 含 / carb_bucket_max 不含；NULL 表示不限。
-- 只做温和修正：MODERATE 可上下挪一档；HIGH 不因份量降档；LOW 默认不升档（预留行 enabled=0）。
CREATE TABLE meal_portion_adjust_rule (
  id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  base_tier       VARCHAR(16)      NOT NULL COMMENT '修正前档位：LOW|MODERATE|HIGH|UNKNOWN',
  carb_bucket_min DECIMAL(8,1)     NULL     COMMENT '该份碳水克数下限（含），NULL=不限',
  carb_bucket_max DECIMAL(8,1)     NULL     COMMENT '该份碳水克数上限（不含），NULL=不限',
  adjusted_tier   VARCHAR(16)      NOT NULL COMMENT '修正后档位：LOW|MODERATE|HIGH',
  priority        INT UNSIGNED     NOT NULL DEFAULT 100 COMMENT '同桶多行时升序取首个',
  is_enabled      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否启用 0否1是',
  created_by      BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by      BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by      BIGINT UNSIGNED  NULL     COMMENT '删除人',
  deleted_at      DATETIME(3)      NULL     COMMENT '删除时间',
  is_deleted      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_mpar_tier_bucket (base_tier, carb_bucket_min, carb_bucket_max, adjusted_tier),
  CONSTRAINT chk_mpar_base CHECK (base_tier IN ('LOW', 'MODERATE', 'HIGH', 'UNKNOWN')),
  CONSTRAINT chk_mpar_adj CHECK (adjusted_tier IN ('LOW', 'MODERATE', 'HIGH')),
  CONSTRAINT chk_mpar_en CHECK (is_enabled IN (0, 1)),
  CONSTRAINT chk_mpar_del CHECK (is_deleted IN (0, 1)),
  CONSTRAINT chk_mpar_range CHECK (carb_bucket_min IS NULL OR carb_bucket_max IS NULL OR carb_bucket_min < carb_bucket_max)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='适食份量修正规则（碳水克数量级桶 × 基础档位 → 修正档位）';

INSERT INTO meal_portion_adjust_rule
  (base_tier, carb_bucket_min, carb_bucket_max, adjusted_tier, priority, is_enabled,
   created_by, updated_by, gmt_create, gmt_modified, deleted_at)
VALUES
  -- 份量很小（碳水 <10g）：适量食物往「可吃」方向挪一档
  ('MODERATE', NULL, 10.0, 'LOW',    10, 1, 0, 0, NOW(), NOW(), NULL),
  -- 份量很大（碳水 ≥60g）：适量食物往「少吃」方向挪一档
  ('MODERATE', 60.0, NULL, 'HIGH',   20, 1, 0, 0, NOW(), NOW(), NULL),
  -- 预留：LOW + 最大桶最多升到 MODERATE（默认禁用，启用需产品确认）
  ('LOW', 60.0, NULL, 'MODERATE', 30, 0, 0, 0, NOW(), NOW(), NULL)
ON DUPLICATE KEY UPDATE
  carb_bucket_min = VALUES(carb_bucket_min), carb_bucket_max = VALUES(carb_bucket_max),
  adjusted_tier = VALUES(adjusted_tier), priority = VALUES(priority),
  is_enabled = VALUES(is_enabled), gmt_modified = NOW(3);

-- ---------- 5. 适食配置表（聚合参数与文案候选，全部落表不写死代码） ----------
CREATE TABLE meal_suitability_config (
  id           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  config_key   VARCHAR(64)      NOT NULL COMMENT '配置键',
  config_value VARCHAR(512)     NOT NULL COMMENT '配置值；文案池用 | 分隔多个候选',
  remark       VARCHAR(255)     NULL     COMMENT '说明',
  created_by   BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '创建人',
  gmt_create   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_by   BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '更新人',
  gmt_modified DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_by   BIGINT UNSIGNED  NULL     COMMENT '删除人',
  deleted_at   DATETIME(3)      NULL     COMMENT '删除时间',
  is_deleted   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  CONSTRAINT pk_id PRIMARY KEY (id),
  UNIQUE KEY uk_msc_key (config_key),
  CONSTRAINT chk_msc_key CHECK (config_key <> ''),
  CONSTRAINT chk_msc_del CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='适食判定配置（聚合参数 / 档位文案池 / 重点关注文案）';

INSERT INTO meal_suitability_config (config_key, config_value, remark,
  created_by, updated_by, gmt_create, gmt_modified, deleted_at)
VALUES
  -- 整餐聚合：碳水占比 ≥ 门槛的项才参与「占比最大」竞选；0 = 全部参与
  ('meal_carb_share_threshold', '0', '整餐定档的碳水占比门槛（0-1 小数，0=不限）',
   0, 0, NOW(), NOW(), NULL),
  -- UNKNOWN 项是否跳过整餐定档（1=跳过取下一占比项，0=占比最大为 UNKNOWN 时整餐 UNKNOWN）
  ('meal_unknown_skip_enabled', '1', '整餐聚合是否跳过 UNKNOWN 项',
   0, 0, NOW(), NOW(), NULL),
  -- 档位兜底建议池（规则无 advice_text 时按序轮换，避免多项同话）
  ('advice_low',      '{foodName}每100g升糖负荷低，可按常量食用|{foodName}升糖负担低，注意整体搭配即可',
   'LOW 档兜底建议候选池（| 分隔，支持 {foodName}/{carbG} 占位符）',
   0, 0, NOW(), NOW(), NULL),
  ('advice_moderate', '{foodName}每100g升糖负荷中等，建议控制份量|{foodName}升糖负担中等，搭配蔬菜和蛋白质更好',
   'MODERATE 档兜底建议候选池',
   0, 0, NOW(), NOW(), NULL),
  ('advice_high',     '{foodName}每100g升糖负荷偏高，建议少量或替换|{foodName}升糖负担较高，减少份量或换低升糖主食',
   'HIGH 档兜底建议候选池',
   0, 0, NOW(), NOW(), NULL),
  ('advice_unknown',  '缺少 GI 或碳水信息，暂无法判断{foodName}的升糖负担',
   'UNKNOWN 档兜底建议候选池',
   0, 0, NOW(), NOW(), NULL),
  -- 重点关注文案（{foodName} 占位符）
  ('focus_text_intrinsic', '重点关注：{foodName} 升糖负担高，建议少量或替换',
   '单项本身 per100gGL 偏高时的整餐标记文案',
   0, 0, NOW(), NOW(), NULL),
  ('focus_text_portion',   '重点关注：{foodName} 份量偏大，升糖负担高',
   '单项因份量修正升为 HIGH 时的整餐标记文案',
   0, 0, NOW(), NOW(), NULL)
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value), remark = VALUES(remark), gmt_modified = NOW(3);

-- ---------- 6. meal_record 增加重点关注清单快照列 ----------
-- 存 JSON 数组：[{"foodName":"薯条","reason":"INTRINSIC","text":"重点关注：薯条 升糖负担高…"}]
ALTER TABLE meal_record
  ADD COLUMN analysis_focus VARCHAR(512) NULL
    COMMENT '重点关注项快照（JSON 数组：foodName/reason/text），无 HIGH 项为 NULL'
    AFTER analysis_summary;
