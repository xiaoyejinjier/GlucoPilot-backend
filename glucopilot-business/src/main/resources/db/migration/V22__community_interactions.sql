-- Explicit publisher identities; never infer a C-user from an ops created_by.
CREATE TABLE community_blogger (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NULL,
  nickname VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(512) NULL,
  bio VARCHAR(256) NOT NULL DEFAULT '',
  enabled TINYINT NOT NULL DEFAULT 1,
  CONSTRAINT chk_cb_enabled CHECK(enabled IN (0,1))
);
INSERT INTO community_blogger(id,nickname,bio) VALUES(1,'社区编辑部','社区资讯与健康知识分享');
ALTER TABLE cms_news ADD COLUMN blogger_id BIGINT NOT NULL DEFAULT 1;

CREATE TABLE community_follow (
  follower_id BIGINT NOT NULL,
  target_key VARCHAR(32) NOT NULL,
  following TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(follower_id,target_key),
  CONSTRAINT chk_cf_follow CHECK(following IN (0,1)),
  INDEX idx_cf_list(follower_id,following,updated_at)
);
CREATE TABLE community_comment (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  news_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  content VARCHAR(500) NOT NULL,
  client_request_id VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_cc_request(user_id,client_request_id),
  INDEX idx_cc_news(news_id,is_deleted,id),
  CONSTRAINT chk_cc_deleted CHECK(is_deleted IN (0,1))
);
CREATE TABLE community_message (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  sender_id BIGINT NOT NULL,
  recipient_id BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  client_request_id VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  read_at DATETIME(3) NULL,
  UNIQUE KEY uk_cm_request(sender_id,client_request_id),
  INDEX idx_cm_out(sender_id,recipient_id,id),
  INDEX idx_cm_in(recipient_id,sender_id,id),
  CONSTRAINT chk_cm_pair CHECK(sender_id <> recipient_id)
);
