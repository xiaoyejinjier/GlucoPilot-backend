-- 所有账号都可按需开通；这里只记录开通状态，资金账本和支付另行接入。
ALTER TABLE user_account
    ADD COLUMN wallet_activated_at DATETIME(3) NULL COMMENT '本人首次开通钱包的时间';
