-- =====================================================================
-- V87 适食规则单项建议文案
-- 只新增可空列并补充占位规则的文案；判定阈值、优先级、规则版本均不变。
-- =====================================================================

ALTER TABLE food_suitability_rule
  ADD COLUMN advice_text VARCHAR(128) NULL
  COMMENT '单项建议文案（规则级，不含食物名）；为空时由服务端按档位兜底'
  AFTER tier;

UPDATE food_suitability_rule
SET advice_text = '升糖速度偏快，建议减少份量或替换为低升糖食物'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'GI_HIGH_70';

UPDATE food_suitability_rule
SET advice_text = '零食类升糖负担较高，建议少量食用或换成升糖负担较低的食物'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT9_SNACK_HIGH';

UPDATE food_suitability_rule
SET advice_text = '含糖饮品升糖较快，建议换用无糖饮品'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT8_DRINK_HIGH';

UPDATE food_suitability_rule
SET advice_text = '蔬菜类升糖负担较低，可保持当前份量'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT3_VEG_LOW';

UPDATE food_suitability_rule
SET advice_text = '肉蛋类升糖负担较低，可保持当前份量'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT2_PROTEIN_LOW';

UPDATE food_suitability_rule
SET advice_text = '豆制品升糖负担较低，可保持当前份量'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT6_SOY_LOW';

UPDATE food_suitability_rule
SET advice_text = '主食类建议控制在半碗左右，搭配杂粮或蔬菜更好'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT1_STAPLE_MODERATE';

UPDATE food_suitability_rule
SET advice_text = '水果类注意份量，建议分次食用'
WHERE rule_version = '2026.11.0-placeholder' AND rule_code = 'CAT4_FRUIT_MODERATE';
