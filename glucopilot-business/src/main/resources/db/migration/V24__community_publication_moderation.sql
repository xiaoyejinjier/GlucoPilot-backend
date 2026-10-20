ALTER TABLE cms_news ADD COLUMN community_managed TINYINT NOT NULL DEFAULT 0;

CREATE TABLE community_article (
  news_id BIGINT NOT NULL PRIMARY KEY,
  author_user_id BIGINT NULL,
  client_request_id VARCHAR(64) NULL,
  seed_key VARCHAR(64) NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'AI_PENDING',
  revision INT NOT NULL DEFAULT 1,
  ai_verdict VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  ai_reason VARCHAR(1000) NOT NULL DEFAULT '',
  ai_model VARCHAR(128) NOT NULL DEFAULT '',
  ai_checked_at DATETIME(3) NULL,
  review_note VARCHAR(1000) NOT NULL DEFAULT '',
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ca_request(author_user_id,client_request_id),
  UNIQUE KEY uk_ca_seed(seed_key),
  INDEX idx_ca_queue(state,updated_at),
  INDEX idx_ca_author(author_user_id,news_id)
);
CREATE TABLE community_tag (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(32) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1
);
INSERT INTO community_tag(code,name,sort_no) VALUES
 ('diet','饮食控糖',1),('type1','1型糖尿病',2),('type2','2型糖尿病',3),
 ('medication','药物科普',4),('exercise','运动管理',5),('daily','日常管理',6);
CREATE TABLE community_article_tag (
  news_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  PRIMARY KEY(news_id,tag_id),
  INDEX idx_cat_tag(tag_id,news_id)
);
CREATE TABLE community_report (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  reporter_id BIGINT NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  reason VARCHAR(32) NOT NULL,
  details VARCHAR(500) NOT NULL DEFAULT '',
  client_request_id VARCHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  resolution VARCHAR(32) NULL,
  resolution_note VARCHAR(1000) NOT NULL DEFAULT '',
  resolved_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at DATETIME(3) NULL,
  UNIQUE KEY uk_cr_request(reporter_id,client_request_id),
  INDEX idx_cr_queue(state,id)
);
CREATE TABLE community_moderation_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  revision INT NULL,
  actor_type VARCHAR(16) NOT NULL,
  actor_id BIGINT NULL,
  decision VARCHAR(32) NOT NULL,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_cml_target(target_type,target_id,id)
);
