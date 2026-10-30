-- V48：给模型配置加「能力集合」列
--
-- 背景：后台一条模型配置原来只知道"厂商 + 端点"，于是"这条能不能看图"只能靠
-- model_endpoint 与 ai.vision.model 是否相同来猜。猜错的代价很具体：
--   1) 新的视觉模型（端点与 yaml 默认值不同名）会被当成文本候选，标了默认就抢走文本通道；
--   2) "设为默认"时清理旧默认不分通道，会把另一个通道的默认一起清掉；
--   3) 视觉通道无法在后台自由新增——它只认 yaml 里那一个模型名。
--
-- 这里存"能力集合"而不是"文本/视觉"二值，是为了表达两件二值表达不了的事：
--   a) 一个多模态模型同时能当文本模型用；
--   b) 某些场景要求"这个模型必须支持工具调用"（能力 ⊇ 场景要求 才允许绑定）。
--
-- 取值：text / vision / tool / stream，逗号分隔；NULL 或空串一律视为「只有 text」。
-- 读路径（AiModelCapability.parse）宽松解析、写路径（parseStrict）严格校验。
--
-- 说明：本列由 AI 侧本批次引入，与 provider_code（厂商=协议）正交：
-- provider_code 回答"用什么协议连"，capability 回答"这个模型能干什么"。

ALTER TABLE ai_model_config
    ADD COLUMN capability VARCHAR(64) NULL
        COMMENT '模型能力集合，逗号分隔：text 文本 / vision 视觉 / tool 工具调用 / stream 流式；NULL 视为 text'
        AFTER provider_code;

-- 回填：视觉模型按端点识别（与 yaml ai.vision.model 的默认值一致）。
-- 若某个部署改过 ai.vision.model，这一行不会命中，需在后台把该行能力改成 vision,stream。
UPDATE ai_model_config
   SET capability = 'vision,stream'
 WHERE capability IS NULL
   AND model_endpoint = 'qwen3-vl-plus';

-- 其余（含历史行）按最保守的文本能力处理：DeepSeek 全系支持工具调用与流式。
UPDATE ai_model_config
   SET capability = 'text,tool,stream'
 WHERE capability IS NULL OR TRIM(capability) = '';
