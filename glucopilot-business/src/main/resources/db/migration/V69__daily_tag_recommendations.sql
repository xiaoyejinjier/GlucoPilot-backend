ALTER TABLE news_user_action
    ADD COLUMN liked_at DATETIME(3) NULL,
    ADD COLUMN collected_at DATETIME(3) NULL,
    ADD INDEX idx_nua_daily_like(user_id,liked_at,news_id),
    ADD INDEX idx_nua_daily_collect(user_id,collected_at,news_id);
-- 旧表没有独立操作时间，只对现存有效状态回填原修改时间。
-- 显式保留gmt_modified，避免MySQL的ON UPDATE将旧互动误改为今天。
UPDATE news_user_action SET liked_at=gmt_modified,gmt_modified=gmt_modified WHERE is_liked=1;
UPDATE news_user_action SET collected_at=gmt_modified,gmt_modified=gmt_modified WHERE is_collected=1;
CREATE INDEX idx_cav_user_day ON community_article_view(user_id,view_date,news_id);
CREATE TABLE community_recommendation_exposure (
    user_id BIGINT NOT NULL,
    news_id BIGINT NOT NULL,
    last_shown_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(user_id,news_id),
    KEY idx_cre_user_time(user_id,last_shown_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐下发记录，不计入文章阅读次数';
