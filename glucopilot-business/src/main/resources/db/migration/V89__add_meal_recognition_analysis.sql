-- 阶段 2b-2：餐食记录补充「识别/分析快照」列（对应方案 2.2）。
-- 全部可空、不回填历史；旧代码不读这些列，可安全回滚。
-- analysis_rule_version 方案写 VARCHAR(16)，但实际 rule_version 形如 2026.11.0-placeholder（21 字符），
-- 与 V88 的 food_suitability_rule.rule_version 对齐取 VARCHAR(32)，避免写入截断。
-- analysis_status 保持可空：历史记录未分析过，不能默认成 0（待分析）。

ALTER TABLE meal_record
    ADD COLUMN recognition_model      VARCHAR(64)  NULL COMMENT '识别模型标识，如 qwen-vl' AFTER recommendation_status,
    ADD COLUMN recognition_at         DATETIME(3)  NULL COMMENT '识别完成时间' AFTER recognition_model,
    ADD COLUMN confirmed_at           DATETIME(3)  NULL COMMENT '用户确认时间' AFTER recognition_at,
    ADD COLUMN analysis_status        TINYINT      NULL COMMENT '分析状态：0待分析 1已生成 2失败，可空=历史未分析' AFTER confirmed_at,
    ADD COLUMN analysis_tier          VARCHAR(16)  NULL COMMENT '整餐档位：LOW|MODERATE|HIGH|UNKNOWN' AFTER analysis_status,
    ADD COLUMN analysis_summary       VARCHAR(255) NULL COMMENT '整餐摘要（通用文案）' AFTER analysis_tier,
    ADD COLUMN analysis_rule_version  VARCHAR(32)  NULL COMMENT '判定所用规则版本，历史可追溯' AFTER analysis_summary,
    ADD COLUMN analysis_generated_at  DATETIME(3)  NULL COMMENT '分析生成时间' AFTER analysis_rule_version;

ALTER TABLE meal_food_item
    ADD COLUMN library_food_id    BIGINT UNSIGNED NULL     COMMENT '营养库待审行ID（AI 估算），可空' AFTER sort_no,
    ADD COLUMN category_code      TINYINT UNSIGNED NULL    COMMENT '食物大类 1-9，可空' AFTER library_food_id,
    ADD COLUMN gi_value           DECIMAL(5,1)    NULL     COMMENT 'GI 快照，可空' AFTER category_code,
    ADD COLUMN gi_source          VARCHAR(16)     NULL     COMMENT 'GI 来源：LIBRARY|CATEGORY_FALLBACK|NONE' AFTER gi_value,
    ADD COLUMN nutrition_source   VARCHAR(16)     NULL     COMMENT '营养来源：LIBRARY|AI_ESTIMATE|MANUAL|UNKNOWN' AFTER gi_source,
    ADD COLUMN confidence         TINYINT UNSIGNED NULL    COMMENT '识别置信度 0-100，可空' AFTER nutrition_source,
    ADD COLUMN user_corrected     TINYINT UNSIGNED NULL    COMMENT '是否人工修正 0否1是，可空' AFTER confidence,
    ADD COLUMN portion_ratio      DECIMAL(4,2)    NOT NULL DEFAULT 1.00 COMMENT '份量倍数，默认1.00' AFTER user_corrected,
    ADD COLUMN advice_tier        VARCHAR(16)     NULL     COMMENT '单项结论档位 LOW|MODERATE|HIGH|UNKNOWN' AFTER portion_ratio,
    ADD COLUMN advice_reason_code VARCHAR(32)     NULL     COMMENT '单项规则原因码，如 HIGH_GI_STAPLE' AFTER advice_tier,
    ADD COLUMN advice_text        VARCHAR(255)    NULL     COMMENT '单项通用建议文案' AFTER advice_reason_code;
