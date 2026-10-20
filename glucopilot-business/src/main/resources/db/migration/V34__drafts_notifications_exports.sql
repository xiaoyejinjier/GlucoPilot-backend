CREATE TABLE community_article_draft (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(80) NOT NULL DEFAULT '',
 body TEXT NOT NULL,
 tag_codes TEXT NOT NULL,
 custom_tags TEXT NOT NULL,
 image_ids TEXT NOT NULL,
 revision INT NOT NULL DEFAULT 1,
 source_article_id BIGINT NULL,
 source_revision INT NULL,
 client_request_id VARCHAR(64) NOT NULL,
 last_request_id VARCHAR(64) NOT NULL,
 publish_request_id VARCHAR(64) NULL,
 published_news_id BIGINT NULL,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_cad_request(user_id,client_request_id),
 INDEX idx_cad_user(user_id,is_deleted,updated_at)
);
CREATE TABLE community_notification (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 recipient_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 target_id BIGINT NOT NULL,
 news_id BIGINT NULL,
 comment_id BIGINT NULL,
 read_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_cn_event(recipient_id,actor_id,kind,target_id),
 INDEX idx_cn_unread(recipient_id,read_at,id)
);
ALTER TABLE data_export_task ADD COLUMN client_request_id VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_det_request ON data_export_task(user_id,client_request_id);
CREATE TABLE data_export_file (
 task_id BIGINT NOT NULL PRIMARY KEY,
 file_bytes LONGBLOB NOT NULL,
 glucose_count INT NOT NULL,
 meal_count INT NOT NULL
);
