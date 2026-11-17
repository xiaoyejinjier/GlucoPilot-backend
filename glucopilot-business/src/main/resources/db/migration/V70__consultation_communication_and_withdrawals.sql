ALTER TABLE media_file DROP CHECK chk_mf_biz;
ALTER TABLE media_file ADD CONSTRAINT chk_mf_biz CHECK(biz_type IN (1,2,3,4,5,6,7,8,9));
CREATE TABLE consultation_request_image (
    consultation_id BIGINT UNSIGNED NOT NULL,
    media_id BIGINT UNSIGNED NOT NULL,
    sort_no INT NOT NULL,
    PRIMARY KEY(consultation_id,media_id),
    KEY idx_consultation_image_media(media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE consultation_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    consultation_id BIGINT UNSIGNED NOT NULL,
    sender_id BIGINT UNSIGNED NOT NULL,
    sender_role VARCHAR(12) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_consultation_message_request(sender_id,client_request_id),
    KEY idx_consultation_message_case(consultation_id,id),
    CONSTRAINT ck_consultation_message_role CHECK(sender_role IN ('PATIENT','DOCTOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE wallet_sandbox_account ADD COLUMN frozen_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN income_available_cents BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_wallet_frozen_nonnegative CHECK(frozen_cents >= 0),
    ADD CONSTRAINT ck_wallet_income_available CHECK(income_available_cents >= 0 AND income_available_cents <= balance_cents);
-- 历史收入按现有问诊收支净额保守回填；后续收入、消费、冻结分别维护此额度。
UPDATE wallet_sandbox_account a SET income_available_cents=LEAST(a.balance_cents,
    GREATEST(COALESCE((SELECT SUM(e.amount_cents) FROM consultation_wallet_entry e WHERE e.user_id=a.user_id),0),0));
CREATE TABLE wallet_withdrawal_order (
    order_no VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    amount_cents INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(32) NOT NULL DEFAULT 'SANDBOX_SIMULATION',
    client_request_id VARCHAR(64) NOT NULL,
    review_note VARCHAR(500) NOT NULL DEFAULT '',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    expected_payout_date DATE NULL,
    payout_reference VARCHAR(64) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_withdrawal_request(user_id,client_request_id),
    KEY idx_withdrawal_user(user_id,created_at),
    KEY idx_withdrawal_due(status,expected_payout_date),
    CONSTRAINT ck_withdrawal_amount CHECK(amount_cents BETWEEN 1 AND 100000000),
    CONSTRAINT ck_withdrawal_status CHECK(status IN ('PENDING','APPROVED','REJECTED','COMPLETED')),
    CONSTRAINT ck_withdrawal_sandbox CHECK(provider='SANDBOX_SIMULATION')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wallet_withdrawal_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    amount_cents INT NOT NULL,
    balance_after_cents BIGINT NOT NULL,
    frozen_after_cents BIGINT NOT NULL,
    actor_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_withdrawal_event(order_no,event_type),
    CONSTRAINT ck_withdrawal_event CHECK(event_type IN ('FREEZE','REJECT','PAYOUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wallet_work_calendar (
    calendar_date DATE NOT NULL PRIMARY KEY,
    working_day TINYINT NOT NULL,
    note VARCHAR(100) NOT NULL DEFAULT '',
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_work_calendar_flag CHECK(working_day IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '提现审核',m.menu_id,6,'/doctor-management/withdrawals','C','0','0','doctor:withdrawal:manage','Wallet','migration',NOW()
FROM sys_menu m WHERE m.path='/doctor-management'
    AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='doctor:withdrawal:manage');
