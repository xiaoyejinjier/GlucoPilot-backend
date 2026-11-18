-- V77：把会话摘要绑到便宜模型（批次 3 / T3.1）
--
-- 现状：`ai_scene_config` 里根本没有 SUMMARY 行，摘要走**文本通道默认模型**——而默认是
-- deepseek-v4-pro（最贵的那个）。摘要这件事只做「把一段对话压成事实清单」，
-- 不需要推理能力，用 pro 是纯浪费；而且它每 12 条消息就跑一次（T3.2 之后），
-- 又常常是长输入（≤3000 字原文 + ≤800 字已有摘要）。
--
-- 为什么连 is_enabled 一起改：`deepseek-v4-flash` 在库里是**停用**状态（历史遗留），
-- 停用的模型不参与通道候选池，绑了也解析不到。启用它不会改变文本默认模型
-- （默认仍是 pro，is_default 不动），因此对聊天链路零影响。
--
-- 幂等：两条都是「不满足条件就不动」，重复执行无副作用；运营若已手工改过 model_code 则保留。
--
-- 回滚：DELETE FROM ai_scene_config WHERE scene_code='SUMMARY'; 再删 flyway 里这一行。

UPDATE ai_model_config
   SET is_enabled = 1
 WHERE model_code = 'deepseek-v4-flash'
   AND is_enabled = 0
   AND is_deleted = 0;

INSERT INTO ai_scene_config (scene_code, model_code, temperature, max_tokens, thinking, is_enabled, remark)
SELECT 'SUMMARY', 'deepseek-v4-flash', 0.10, 600, 0, 1,
       'V77：摘要只做压缩，不需要推理大模型（T3.1；温度与 yaml ai.scenes.summary 一致）'
 WHERE NOT EXISTS (SELECT 1 FROM ai_scene_config WHERE scene_code = 'SUMMARY' AND is_deleted = 0);
