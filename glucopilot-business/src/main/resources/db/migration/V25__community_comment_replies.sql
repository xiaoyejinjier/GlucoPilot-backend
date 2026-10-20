ALTER TABLE community_comment ADD COLUMN reply_to_comment_id BIGINT NULL;
ALTER TABLE community_comment ADD COLUMN root_comment_id BIGINT NULL;
CREATE INDEX idx_cc_thread ON community_comment(news_id,root_comment_id,id);
