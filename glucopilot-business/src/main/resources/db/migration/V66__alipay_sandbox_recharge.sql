-- 沙箱测试资金独立保存，绝不与未来真实钱包混用。
CREATE TABLE wallet_sandbox_account (
    user_id BIGINT UNSIGNED NOT NULL,
    balance_cents BIGINT NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(user_id),
    CONSTRAINT ck_sandbox_wallet_balance CHECK(balance_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仅支付宝沙箱测试余额，非真实资金';

CREATE TABLE wallet_sandbox_ledger (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    amount_cents INT UNSIGNED NOT NULL,
    balance_after_cents BIGINT NOT NULL,
    provider_trade_no VARCHAR(96) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_sandbox_credit_order(order_no),
    UNIQUE KEY uk_sandbox_credit_trade(provider_trade_no),
    KEY idx_sandbox_ledger_user(user_id,created_at,id),
    CONSTRAINT ck_sandbox_credit CHECK(amount_cents > 0 AND balance_after_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱充值入账流水，只可幂等入账';

ALTER TABLE wallet_recharge_order
    ADD COLUMN sandbox_app_id VARCHAR(32) NULL,
    ADD COLUMN sandbox_seller_id VARCHAR(32) NULL,
    ADD COLUMN qr_code VARCHAR(2048) NULL,
    ADD COLUMN expires_at DATETIME(3) NULL,
    ADD COLUMN last_query_at DATETIME(3) NULL;
