CREATE TABLE user_reminder_read (
    user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    source_id BIGINT NOT NULL,
    glucose_value DECIMAL(3,1) NULL,
    recorded_at DATETIME(3) NULL,
    replied_at DATETIME(3) NULL,
    reply_hash CHAR(64) NULL,
    read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, kind, source_id),
    CONSTRAINT chk_urr_kind CHECK (kind IN ('GLUCOSE', 'REPLY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
