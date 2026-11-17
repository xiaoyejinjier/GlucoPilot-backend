CREATE TABLE admin_work_reminder_read (
    ops_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    revision VARCHAR(255) NOT NULL,
    read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (ops_id, kind, source_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='各管理员独立的待办来源快照已读回执，不改变处理状态';

ALTER TABLE user_reminder_read
    ADD COLUMN current_glucose_value DECIMAL(4,1) NULL;
ALTER TABLE user_reminder_read
    ADD COLUMN current_recorded_at DATETIME(3) NULL;

-- 已有已读回复保留原测量；当前记录后来改变时再提醒一次。
UPDATE user_reminder_read
SET current_glucose_value = glucose_value, current_recorded_at = recorded_at
WHERE kind = 'REPLY';
