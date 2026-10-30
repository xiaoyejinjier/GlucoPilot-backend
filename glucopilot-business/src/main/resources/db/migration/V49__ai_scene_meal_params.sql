-- V49：把饮食分析的输出上限从 yaml（ai.meal.max-tokens）搬进 ai_scene_config
--
-- 背景：C2 起场景 yaml 只保留「提示词 + 默认参数」，选模型与关键参数一律以 DB 为准。
-- 原 ai.meal.max-tokens=2048 与 ai.vision.max-tokens=512 是两份配置写同一件事，
-- 且 ai.meal.model 与 ai.vision.model 也是同一个值写两遍——都已经删掉。
-- 为了让「行为等价」，这里把 2048 补进场景行（V19 种子只给了 temperature=0.10）。
--
-- 幂等：只在当前为空时回填，重复执行无副作用；运营若已手工改过则不覆盖。
UPDATE ai_scene_config
SET max_tokens = 2048
WHERE scene_code = 'MEAL_ANALYSIS'
  AND max_tokens IS NULL;
