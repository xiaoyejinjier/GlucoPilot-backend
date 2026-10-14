-- AI 模型配置后台化（P0/P1）：把"模型名 / 凭证 / 参数"从 yaml 搬到可在后台实时修改的表。
--
-- 背景：
--   ai_model_config 表从 V1 起就存在，但一直零引用（只有一行占位 default_chat），
--   真正生效的配置在 application.yaml / application-local.yaml 里（spring.ai.deepseek.*、ai.vision.*）。
--   于是"换模型名 / 换 key"都要改文件 + 重新打包 + 重启——2026-10-12 实测踩过：
--   视觉模型名写错（qwen3-vl-flash 在本账号下不存在 → 404 Model not exist），
--   而配置被打进 jar，改了源文件不重新构建就不生效。
--
-- 本迁移只做"结构 + 种子"，**绝不写入任何密钥**（api_key_cipher 保持 NULL）：
--   api_key_cipher 为空 = 沿用环境变量 / yaml 里的密钥（见 AiRuntimeConfigService），
--   运维首次通过后台上传密钥时才写入密文（AES-GCM，主密钥在环境变量 AI_CONFIG_MASTER_KEY）。
--
-- 与 yaml 的关系（避免出现"两处真相"）：
--   yaml 仍是兜底与首次种子来源；DB 里启用且能解析的行优先级更高；
--   启动时若同一 model_code 两边都有且关键字段不同，打 WARN 提示（见 AiRuntimeConfigService）。
--
-- 注意：本迁移不建"场景→模型绑定"表。场景解析仍由 yaml（ai.scenes.*.model-code + 全局默认）决定，
--   DB 行按 model_code 生效；场景绑定 UI 留到 P2，理由是它属于"另一个轴"，混在一期会放大回归面。


-- ---------------------------------------------------------------------------
-- 1) 扩列：加密元数据 / 新参数 / 并发与观测
-- ---------------------------------------------------------------------------
ALTER TABLE ai_model_config
    ADD COLUMN cipher_algo       VARCHAR(32)      NULL COMMENT '密钥加密算法（如 AES-GCM）；NULL 表示密钥为明文时代遗留或未配置',
    ADD COLUMN key_version       INT UNSIGNED     NULL COMMENT '加密主密钥版本，支持轮换',
    ADD COLUMN top_p             DECIMAL(3,2)     NULL COMMENT '核采样；NULL 表示用 provider 默认',
    ADD COLUMN thinking          TINYINT(1)       NULL COMMENT '是否开启推理链（DeepSeek 特有）；NULL 表示不显式设置',
    ADD COLUMN extra_params      JSON             NULL COMMENT '厂商特有参数，避免为每家 provider 加列',
    ADD COLUMN config_version    BIGINT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '配置版本：乐观锁 + 缓存失效判据',
    ADD COLUMN last_test_at      DATETIME(3)      NULL COMMENT '最近一次连通性测试时间',
    ADD COLUMN last_test_ok      TINYINT(1)       NULL COMMENT '最近一次连通性测试是否成功',
    ADD COLUMN last_test_message VARCHAR(512)     NULL COMMENT '最近一次测试结果摘要（脱敏，不含密钥）';

-- model_code 唯一（逻辑删除列参与，沿用本项目的既有写法）：
-- 允许"删除后又用同一个 code 新建"，但不允许同时存在两条未删除的同名配置。
ALTER TABLE ai_model_config
    ADD UNIQUE KEY uk_ai_model_code (model_code, is_deleted);


-- ---------------------------------------------------------------------------
-- 2) 种子：把实际在用的两个模型登记进来（密钥留空 = 沿用 yaml/env）
-- ---------------------------------------------------------------------------
-- 2.1 历史占位行：model_endpoint 写的是 "glucopilot-chat-v1"（不是真实模型名），且全项目零引用。
--     直接停用并改名，让后台一眼看出它不生效；不删除是为了保留这条历史记录可追溯。
UPDATE ai_model_config
   SET model_name = '（历史占位，已停用）默认对话模型',
       is_enabled = 0,
       is_default = 0,
       config_version = config_version + 1
 WHERE model_code = 'default_chat'
   AND is_deleted = 0;

-- 2.2 空库 / 未登记时补一条文本模型
INSERT INTO ai_model_config (model_code, model_name, provider_code, model_endpoint,
                             temperature, max_tokens, timeout_ms, is_default, is_enabled, config_version, remark)
SELECT 'deepseek-v4-flash', 'DeepSeek 文本模型', 1, 'deepseek-v4-flash',
       0.70, 2048, 60000, 1, 1, 1,
       '文本场景默认模型；api_key 留空表示沿用环境变量/yaml'
 WHERE NOT EXISTS (SELECT 1 FROM ai_model_config WHERE model_code = 'deepseek-v4-flash' AND is_deleted = 0);

-- 2.3 视觉模型（OpenAI 兼容协议）
INSERT INTO ai_model_config (model_code, model_name, provider_code, api_base_url, model_endpoint,
                             temperature, max_tokens, timeout_ms, is_default, is_enabled, config_version, remark)
SELECT 'qwen3-vl-plus', '通义千问 VL 视觉模型', 2, 'https://dashscope.aliyuncs.com/compatible-mode/v1',
       'qwen3-vl-plus', 0.20, 512, 60000, 0, 1, 1,
       '视觉场景（图片识别）；本账号实测可用（qwen3-vl-flash/qwen-vl-max 不存在）'
 WHERE NOT EXISTS (SELECT 1 FROM ai_model_config WHERE model_code = 'qwen3-vl-plus' AND is_deleted = 0);


-- ---------------------------------------------------------------------------
-- 3) 后台菜单与权限
--    菜单是给"非超管角色"看的（RuoYi 超管绕过权限校验），perms 才是 @PreAuthorize 的判据。
--    父菜单 + 页面菜单(查询权限) + 按钮权限(可写)，编号取 2xxx 段避免与既有 31 条菜单冲突。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, parent_id, menu_name, order_num, path, component, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2000, 0, 'AI 配置', 9, 'ai', NULL, 1, 0, 'M', '0', '0', NULL, 'robot', 'migration', NOW(),
       'AI 模型配置（后台实时修改模型名/凭证/参数）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2000);

INSERT INTO sys_menu (menu_id, parent_id, menu_name, order_num, path, component, query, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2001, 2000, '模型配置', 1, 'model', 'ai/ModelConfigView', NULL, 1, 0, 'C', '0', '0',
       'ai:model:query', 'list', 'migration', NOW(), '模型列表（密钥只显示掩码）'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2001);

INSERT INTO sys_menu (menu_id, parent_id, menu_name, order_num, path, component, query, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2002, 2001, '模型编辑', 1, '', NULL, NULL, 1, 0, 'F', '0', '0',
       'ai:model:manage', '#', 'migration', NOW(), '新增/修改/启停/设默认/测试连通性'
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2002);

-- 授权给超管之外的后台角色：运营可管，编辑只读（按 role_key 匹配，缺角色则跳过）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM sys_role r
  JOIN sys_menu m ON m.menu_id IN (2000, 2001, 2002)
 WHERE r.role_key IN ('super_admin', 'operator')
   AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = m.menu_id);
