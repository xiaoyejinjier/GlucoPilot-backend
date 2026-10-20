ALTER TABLE media_file DROP CHECK chk_mf_biz;
ALTER TABLE media_file ADD CONSTRAINT chk_mf_biz CHECK (biz_type IN (1,2,3,4,5,6,7,8));
ALTER TABLE community_article_draft ADD COLUMN video_ids TEXT NULL;
UPDATE community_article_draft SET video_ids='[]' WHERE video_ids IS NULL;
ALTER TABLE community_article_draft MODIFY video_ids TEXT NOT NULL;
