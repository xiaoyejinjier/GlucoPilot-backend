CREATE TABLE consultation_message_read (
    user_id BIGINT UNSIGNED NOT NULL,
    consultation_id BIGINT UNSIGNED NOT NULL,
    through_id BIGINT UNSIGNED NOT NULL,
    read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, consultation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
