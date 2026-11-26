-- 仅记录本人已经查看的医生审核版本，不修改审核状态或既有业务数据。
CREATE TABLE IF NOT EXISTS consultation_doctor_review_read (
    user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    review_version BIGINT NOT NULL,
    read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_doctor_review_read_version CHECK (review_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
