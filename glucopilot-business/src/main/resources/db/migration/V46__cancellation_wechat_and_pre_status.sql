-- =============================================================================
--   1. verify_channel 扩展 3=微信：纯微信账号（未绑手机号/邮箱）也可注销，
--      身份复核用 wx.login code 现场换取 openid 与账号比对，不再强制先绑定；
--   2. 新增 pre_status：记录申请前账号状态（0=禁用 1=正常）。
--      登录取消注销时按它还原——DISABLED 账号恢复后仍是 DISABLED，
--      不允许借"注销再恢复"把风控禁用洗白成正常态。
-- =============================================================================

ALTER TABLE user_account_cancellation DROP CHECK chk_uac_channel;
ALTER TABLE user_account_cancellation
  ADD CONSTRAINT chk_uac_channel CHECK (verify_channel IN (1, 2, 3));

ALTER TABLE user_account_cancellation
  ADD COLUMN pre_status TINYINT UNSIGNED NOT NULL DEFAULT 1
    COMMENT '申请前账号状态 0禁用 1正常(恢复时按此还原)' AFTER verify_channel;

ALTER TABLE user_account_cancellation
  ADD CONSTRAINT chk_uac_pre_status CHECK (pre_status IN (0, 1));
