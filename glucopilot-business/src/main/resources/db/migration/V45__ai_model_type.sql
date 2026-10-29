-- AI 模型配置的"用途"维度：一条配置是文本模型还是视觉（多模态）模型。
--
-- 背景（批次 2 要修的静默错误）：
--   V13 之后 ai_model_config 仍然只有"厂商 + 端点"，于是"这条是不是视觉模型"只能靠
--   model_endpoint 与 ai.vision.model 是否相等来猜（AiRuntimeConfigServiceImpl 的 visionLike）。
--   三个具体后果：
--     1) 后台新增一个视觉模型（端点与 yaml 默认值不同名）会被当成文本候选，
--        一旦标了默认就抢走文本通道的默认位，对话侧直接 404；
--     2) "设为默认"时 clearOtherDefaults 不分类型，会顺手清掉另一个通道的默认标记；
--     3) 视觉通道无法在后台自助配置——它只认 ai.vision.model 那一个名字。
--
-- 本迁移只做"加列 + 回填"，不动任何其它字段：
--   model_type 允许 NULL（= 视为 TEXT），以便旧代码/旧前端在灰度期仍可写入；
--   回填规则用 model_endpoint 与 yaml 里 ai.vision.model 的默认值（qwen3-vl-plus）比对。
--   ⚠️ 若某部署把 AI_VISION_MODEL 改成了别的名字，那行会落成 TEXT，
--      需要管理员在后台把「用途」改成视觉（后台列表会显示该字段，改一次即可自愈）。
--
-- 与通道（channel）的关系：通道决定连接与凭据（AiChannelProperties），
--   类型决定"这个模型能干什么"。同一个视觉模型既可以被视觉通道调用，
--   也可以被某个场景按 model_code 绑定——两者正交，故不合并成一个字段。


-- ---------------------------------------------------------------------------
-- 1) 加列
-- ---------------------------------------------------------------------------
ALTER TABLE ai_model_config
    ADD COLUMN model_type VARCHAR(16) NULL COMMENT '模型用途：TEXT=文本 / VISION=视觉（多模态）；NULL 视为 TEXT' AFTER provider_code;


-- ---------------------------------------------------------------------------
-- 2) 回填
-- ---------------------------------------------------------------------------
-- 2.1 端点等于 yaml 视觉默认值的行 → 视觉
UPDATE ai_model_config
SET model_type = 'VISION'
WHERE model_type IS NULL
  AND model_endpoint = 'qwen3-vl-plus';

-- 2.2 其余历史行 → 文本
UPDATE ai_model_config
SET model_type = 'TEXT'
WHERE model_type IS NULL;
