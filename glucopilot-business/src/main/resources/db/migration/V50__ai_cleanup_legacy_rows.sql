-- V50：清理两处历史坏数据
--
-- 1) ai_model_config.default_chat —— V2 播种的占位模型，model_endpoint = 'glucopilot-chat-v1'
--    在厂商侧根本不存在（当时的想法是"先占个位，等接入真模型再改"）。它早就 is_enabled = 0，
--    但后台列表里仍然显示，新人会以为可以直接启用。这里逻辑删除并改写 remark 说明来历。
-- 2) ai_prompt_template 的 3 条 legacy_* —— V16/V18 把场景编码改名的中间产物，scene_code
--    不在 SceneCode 枚举里，运行时不会命中，只会在后台提示词列表里制造噪音。
--    CHAT 的 v2（id=11）属于真实历史版本，保留可回滚，不在本次清理范围内。
--
-- 两条 UPDATE 都带 is_deleted = 0 守卫，重复执行安全；逻辑删除而非物理删除，需要时可恢复。

UPDATE ai_model_config
SET is_enabled  = 0,
    is_deleted  = 1,
    deleted_at  = CURRENT_TIMESTAMP,
    deleted_by  = NULL,
    remark      = '历史占位模型：model_endpoint = glucopilot-chat-v1 在厂商侧不存在，V2 播种后从未真正可用；V50 逻辑删除'
WHERE BINARY model_code = 'default_chat'
  AND is_deleted = 0;

UPDATE ai_prompt_template
SET is_enabled  = 0,
    is_deleted  = 1,
    deleted_at  = CURRENT_TIMESTAMP,
    deleted_by  = NULL,
    change_note = CONCAT(COALESCE(change_note, ''), '；V50 逻辑删除（场景编码不在 SceneCode 枚举内，运行时不会命中）')
WHERE BINARY scene_code IN ('legacy_chat_system', 'legacy_meal_analysis', 'legacy_glucose_advice')
  AND is_deleted = 0;
