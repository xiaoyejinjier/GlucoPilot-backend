-- V101：适食判定的文案与量无关（2026-11-27 产品决策）
--
-- 背景：份量（克重）彻底退出判定，判定只回答「这一餐该不该吃、每样食物该不该吃」。
-- 原来那批文案却处处提到份量（「建议减少份量」「可保持当前份量」「注意份量」「建议控制在半碗左右」），
-- 与新的判定口径矛盾：既然不再分析量，就不该在结论里对量下判断。
--
-- 本次只改文案，不改判定规则与表结构：
--   1. food_suitability_rule.advice_text：11 条规则文案全部去掉份量表述；
--   2. meal_suitability_config：advice_* 与 focus_text_intrinsic 同步，并删除 focus_text_portion
--      （份量偏大这条原因已下线，留着会被当成「还能触发」）；
--   3. meal_carb_share_threshold / meal_unknown_skip_enabled 两个键保留不删：
--      代码已不再读取，删掉反而让回滚失去现场，remark 里注明已失效即可。
--
-- 文案口径（产品决策，替代此前「禁止建议食用类表述」的旧口径）：
--   三档分别表达「建议食用 / 适量食用 / 不建议食用」，仍附「仅供参考，不构成医疗建议」免责声明。

UPDATE food_suitability_rule
SET advice_text = CASE rule_code
    WHEN 'CAT1_STAPLE_MODERATE' THEN '主食类升糖负担中等，可搭配杂粮或蔬菜一起吃'
    WHEN 'CAT2_PROTEIN_LOW' THEN '肉蛋类升糖负担较低，可正常食用'
    WHEN 'CAT3_VEG_LOW' THEN '蔬菜类升糖负担较低，可正常食用'
    WHEN 'CAT4_FRUIT_MODERATE' THEN '水果类升糖负担中等，可适量食用并分次吃'
    WHEN 'CAT6_SOY_LOW' THEN '豆制品升糖负担较低，可正常食用'
    WHEN 'CAT8_DRINK_HIGH' THEN '含糖饮品升糖较快，建议换用无糖饮品'
    WHEN 'CAT9_SNACK_HIGH' THEN '零食类升糖负担较高，建议换用升糖负担较低的食物'
    WHEN 'GI_HIGH_70' THEN '升糖速度偏快，建议换用升糖负担更低的食物'
    WHEN 'GL_ITEM_HIGH' THEN '{foodName}每100g升糖负荷偏高，不建议食用'
    WHEN 'GL_ITEM_LOW' THEN '{foodName}每100g升糖负荷低，建议食用'
    WHEN 'GL_ITEM_MODERATE' THEN '{foodName}每100g升糖负荷中等，建议适量食用'
    ELSE advice_text
END
WHERE rule_code IN ('CAT1_STAPLE_MODERATE', 'CAT2_PROTEIN_LOW', 'CAT3_VEG_LOW', 'CAT4_FRUIT_MODERATE',
                    'CAT6_SOY_LOW', 'CAT8_DRINK_HIGH', 'CAT9_SNACK_HIGH', 'GI_HIGH_70',
                    'GL_ITEM_HIGH', 'GL_ITEM_LOW', 'GL_ITEM_MODERATE');

UPDATE meal_suitability_config
SET config_value = '{foodName}每100g升糖负荷低，建议食用｜{foodName}升糖负担低，可正常食用'
WHERE config_key = 'advice_low';

UPDATE meal_suitability_config
SET config_value = '{foodName}每100g升糖负荷中等，建议适量食用｜{foodName}升糖负担中等，可搭配蔬菜和蛋白质'
WHERE config_key = 'advice_moderate';

UPDATE meal_suitability_config
SET config_value = '{foodName}每100g升糖负荷偏高，不建议食用｜{foodName}升糖负担较高，建议换用升糖负担更低的食物'
WHERE config_key = 'advice_high';

UPDATE meal_suitability_config
SET config_value = '缺少 GI 或碳水信息，暂无法判断{foodName}的升糖负担'
WHERE config_key = 'advice_unknown';

UPDATE meal_suitability_config
SET config_value = '重点关注：{foodName} 升糖负担高，建议换用升糖负担更低的食物'
WHERE config_key = 'focus_text_intrinsic';

DELETE FROM meal_suitability_config WHERE config_key = 'focus_text_portion';

UPDATE meal_suitability_config
SET remark = '份量退出判定后已失效（2026-11-27，V101）：引擎不再读取，仅保留现场便于回滚'
WHERE config_key IN ('meal_carb_share_threshold', 'meal_unknown_skip_enabled');
