ALTER TABLE user_profile ADD COLUMN bio VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE community_article_draft ADD COLUMN visibility VARCHAR(16) NULL;
ALTER TABLE community_article_draft ADD COLUMN viewer_ids TEXT NULL;

CREATE TABLE community_article_access (
  news_id BIGINT NOT NULL PRIMARY KEY,
  visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
  revision INT NOT NULL DEFAULT 1,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_caa_visibility CHECK (visibility IN ('PUBLIC','PRIVATE','FRIENDS'))
);
CREATE TABLE community_article_reader (
  news_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY(news_id,user_id),
  KEY idx_car_user(user_id,news_id)
);
CREATE TABLE community_article_view (
  news_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  view_date DATE NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(news_id,user_id,view_date)
);
CREATE TABLE community_article_hidden (
  user_id BIGINT NOT NULL,
  news_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(user_id,news_id)
);
