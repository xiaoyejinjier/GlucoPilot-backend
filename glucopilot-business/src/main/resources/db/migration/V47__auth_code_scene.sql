-- =============================================================================
--   验证码 scene 加固：auth_sms_code / auth_email_code 落库发送场景，
--   消费时按 scene 精确匹配，避免登录码被拿去注销、注销码被拿去绑定。
--   存量数据默认 login（项目未上线，无历史数据依赖）。
-- =============================================================================

ALTER TABLE auth_sms_code
  ADD COLUMN scene VARCHAR(16) NOT NULL DEFAULT 'login'
    COMMENT '发送场景 login/bind/cancel/recover' AFTER verify_code;

ALTER TABLE auth_sms_code
  ADD KEY idx_asc_phone_scene_used (phone, scene, is_used);

ALTER TABLE auth_email_code
  ADD COLUMN scene VARCHAR(16) NOT NULL DEFAULT 'login'
    COMMENT '发送场景 login/bind/cancel/recover' AFTER verify_code;

ALTER TABLE auth_email_code
  ADD KEY idx_aec_email_scene_used (email, scene, is_used);
