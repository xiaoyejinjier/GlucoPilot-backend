package com.threadfit.glucopilot.community.media.mapper;

import org.apache.ibatis.annotations.*;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityArticleImageMapper {

    @Select(
            "SELECT COUNT(*) FROM user_account WHERE id=#{viewer} AND is_deleted=0 AND"
                    + " account_status=1 ")
    Long countActiveUser(@Param("viewer") Long viewer);

    @Select(
            "SELECT COUNT(*) FROM community_article_image i JOIN cms_news n ON n.id=i.news_id WHERE"
                    + " i.news_id=#{articleId} AND i.media_id=#{mediaId} AND n.is_deleted=0 ")
    Long countArticleMedia(@Param("articleId") long articleId, @Param("mediaId") long mediaId);

    @Select(
            "SELECT COUNT(*) FROM community_article_image i JOIN cms_news n ON n.id=i.news_id JOIN"
                + " community_article a ON a.news_id=n.id WHERE i.media_id=#{mediaId} AND"
                + " a.state='PUBLISHED' AND n.is_deleted=0 AND n.publish_status=1 AND"
                + " n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR EXISTS(SELECT 1"
                + " FROM community_article pa LEFT JOIN user_account au ON au.id=pa.author_user_id"
                + " AND au.is_deleted=0 AND au.account_status=1 WHERE pa.news_id=n.id AND"
                + " pa.state='PUBLISHED' AND (au.id IS NOT NULL OR (pa.author_user_id IS NULL AND"
                + " EXISTS(SELECT 1 FROM community_blogger eb WHERE eb.id=n.blogger_id AND"
                + " eb.enabled=1 AND eb.user_id IS NULL))))) AND  (NOT EXISTS(SELECT 1 FROM"
                + " community_article_access ac WHERE ac.news_id=n.id AND ac.visibility<>'PUBLIC')"
                + " OR (SELECT va.author_user_id FROM community_article va WHERE"
                + " va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1 FROM community_article_access ac"
                + " JOIN community_article_reader ar ON ar.news_id=ac.news_id WHERE ac.news_id=n.id"
                + " AND ac.visibility='FRIENDS' AND ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.target_key=CONCAT('u',(SELECT va.author_user_id FROM community_article va"
                + " WHERE va.news_id=n.id)) AND f.following=1) AND EXISTS(SELECT 1 FROM"
                + " community_follow f WHERE f.follower_id=(SELECT va.author_user_id FROM"
                + " community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1)))  AND NOT EXISTS(SELECT"
                + " 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                + " ah.user_id=#{viewer}) ")
    Long countReadableMedia(@Param("mediaId") long mediaId, @Param("viewer") Long viewer);
}
