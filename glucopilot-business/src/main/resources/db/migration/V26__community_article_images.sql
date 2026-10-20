ALTER TABLE media_file DROP CHECK chk_mf_biz;
ALTER TABLE media_file ADD CONSTRAINT chk_mf_biz CHECK (biz_type IN (1,2,3,4,5,6,7));

CREATE TABLE community_article_image (
    news_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_no INT NOT NULL,
    PRIMARY KEY (news_id, media_id),
    UNIQUE KEY uk_cai_order (news_id, sort_no),
    KEY idx_cai_media (media_id, news_id)
);
