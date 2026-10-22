-- 评论点赞：C 端用户对 community_comment 的点赞关系表。
-- 语义同 community_follow.following：目标态 PUT 翻转（liked 1/0），取消=置 0，不删行；
-- 一对 (user_id, comment_id) 至多一行，硬清理不做。
-- 口径约定：注销用户点赞行保留且计入显示数，与 news_user_action 一致；点赞计数查询禁止 join user_account 过滤。
CREATE TABLE community_comment_like (
  id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT      NOT NULL,
  comment_id        BIGINT      NOT NULL,
  liked             TINYINT     NOT NULL DEFAULT 1,
  client_request_id VARCHAR(64) NOT NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ccl_pair (user_id, comment_id),
  KEY idx_ccl_comment (comment_id, liked),
  CONSTRAINT chk_ccl_liked CHECK (liked IN (0, 1))
);
