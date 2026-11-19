ALTER TABLE wallet_withdrawal_order DROP CHECK ck_withdrawal_sandbox;
ALTER TABLE wallet_withdrawal_order
    MODIFY provider VARCHAR(32) NOT NULL DEFAULT 'ALIPAY_SANDBOX_TRANSFER',
    ADD COLUMN payee_user_id VARCHAR(32) NULL,
    ADD COLUMN sandbox_app_id VARCHAR(32) NULL,
    ADD COLUMN sandbox_seller_id VARCHAR(32) NULL,
    ADD COLUMN transfer_state VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN transfer_error_code VARCHAR(64) NULL,
    ADD COLUMN transfer_started_at DATETIME(3) NULL,
    ADD COLUMN last_transfer_at DATETIME(3) NULL,
    ADD COLUMN transfer_lease_until DATETIME(3) NULL,
    ADD UNIQUE KEY uk_withdrawal_provider_reference(provider,payout_reference),
    ADD CONSTRAINT ck_withdrawal_sandbox CHECK(provider IN ('SANDBOX_SIMULATION','ALIPAY_SANDBOX_TRANSFER')),
    ADD CONSTRAINT ck_withdrawal_transfer_state CHECK(transfer_state IN ('NOT_STARTED','PROCESSING','WAITING','UNKNOWN','SUCCESS','FAILED','LEGACY'));
UPDATE wallet_withdrawal_order SET transfer_state='LEGACY' WHERE provider='SANDBOX_SIMULATION';
CREATE TABLE wallet_withdrawal_transfer_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    transfer_state VARCHAR(16) NOT NULL,
    provider_code VARCHAR(64) NULL,
    actor_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_withdrawal_transfer_order(order_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱转账诊断，仅记录状态及安全错误码，不保存密钥或完整报文';
