CREATE TABLE consultation_doctor (
    user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    real_name VARCHAR(50) NOT NULL,
    hospital VARCHAR(120) NOT NULL,
    specialty VARCHAR(80) NOT NULL,
    license_number VARCHAR(80) NOT NULL,
    qualification_note VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    review_note VARCHAR(500) NOT NULL DEFAULT '',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_doctor_status(status, user_id),
    CONSTRAINT ck_doctor_status CHECK(status IN ('PENDING','APPROVED','REJECTED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consultation_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    patient_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(80) NOT NULL,
    topic VARCHAR(16) NOT NULL,
    symptoms VARCHAR(3000) NOT NULL,
    glucose_note VARCHAR(300) NOT NULL DEFAULT '',
    medication_note VARCHAR(1000) NOT NULL DEFAULT '',
    anonymous TINYINT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    doctor_user_id BIGINT UNSIGNED NULL,
    client_request_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claimed_at DATETIME(3) NULL,
    UNIQUE KEY uk_consultation_request(patient_id, client_request_id),
    KEY idx_consultation_patient(patient_id, id),
    KEY idx_consultation_doctor(doctor_user_id, id),
    KEY idx_consultation_feed(topic, id),
    CONSTRAINT ck_consultation_topic CHECK(topic IN ('GLUCOSE','DIET','MEDICATION','CHECKUP')),
    CONSTRAINT ck_consultation_state CHECK(status IN ('WAITING','IN_PROGRESS','REPORT_READY','CLOSED')),
    CONSTRAINT ck_consultation_pair CHECK(doctor_user_id IS NULL OR doctor_user_id <> patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consultation_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    consultation_id BIGINT UNSIGNED NOT NULL,
    doctor_user_id BIGINT UNSIGNED NOT NULL,
    doctor_name VARCHAR(50) NOT NULL,
    specialty VARCHAR(80) NOT NULL,
    assessment VARCHAR(3000) NOT NULL,
    medication_advice VARCHAR(2000) NOT NULL,
    follow_up_advice VARCHAR(2000) NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_consultation_report(consultation_id),
    UNIQUE KEY uk_doctor_report_request(doctor_user_id, client_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 同一订单必须同时产生患者支出和医生收入，资金写入使用同一事务。
CREATE TABLE consultation_wallet_entry (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    amount_cents INT NOT NULL,
    balance_after_cents BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_consultation_wallet_entry(order_no, user_id),
    KEY idx_consultation_wallet_user(user_id, id),
    CONSTRAINT ck_consultation_entry CHECK(amount_cents IN (-100,100) AND balance_after_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
