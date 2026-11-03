package com.threadfit.glucopilot.community.reaction.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityReactionMapper {

    @Select(
            "<script>SELECT COUNT(*) FROM news_user_action a JOIN cms_news n ON n.id=a.news_id"
                + " WHERE a.user_id=#{viewer} AND a.is_deleted=0 <choose><when test=\"liked\"> AND"
                + " a.is_liked=1</when><otherwise> AND a.is_collected=1</otherwise></choose> AND"
                + " n.is_deleted=0 AND n.publish_status=1 AND n.publish_at&lt;=CURRENT_TIMESTAMP"
                + " AND (n.community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa LEFT"
                + " JOIN user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND"
                + " au.account_status=1 WHERE pa.news_id=n.id AND pa.state='PUBLISHED' AND (au.id"
                + " IS NOT NULL OR (pa.author_user_id IS NULL AND EXISTS(SELECT 1 FROM"
                + " community_blogger eb WHERE eb.id=n.blogger_id AND eb.enabled=1 AND eb.user_id"
                + " IS NULL))))) AND  (NOT EXISTS(SELECT 1 FROM community_article_access ac WHERE"
                + " ac.news_id=n.id AND ac.visibility&lt;&gt;'PUBLIC') OR (SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1"
                + " FROM community_article_access ac JOIN community_article_reader ar ON"
                + " ar.news_id=ac.news_id WHERE ac.news_id=n.id AND ac.visibility='FRIENDS' AND"
                + " ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=#{viewer} AND f.target_key=CONCAT('u',(SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)) AND f.following=1) AND"
                + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=(SELECT"
                + " va.author_user_id FROM community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1)))  AND NOT EXISTS(SELECT"
                + " 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                + " ah.user_id=#{viewer})  AND LOCATE(#{keyword},n.title)>0</script> ")
    Long countActionArticles(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("liked") boolean liked);

    @Select(
            "<script>SELECT n.* FROM news_user_action a JOIN cms_news n ON n.id=a.news_id WHERE"
                + " a.user_id=#{viewer} AND a.is_deleted=0 <choose><when test=\"liked\"> AND"
                + " a.is_liked=1</when><otherwise> AND a.is_collected=1</otherwise></choose> AND"
                + " n.is_deleted=0 AND n.publish_status=1 AND n.publish_at&lt;=CURRENT_TIMESTAMP"
                + " AND (n.community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa LEFT"
                + " JOIN user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND"
                + " au.account_status=1 WHERE pa.news_id=n.id AND pa.state='PUBLISHED' AND (au.id"
                + " IS NOT NULL OR (pa.author_user_id IS NULL AND EXISTS(SELECT 1 FROM"
                + " community_blogger eb WHERE eb.id=n.blogger_id AND eb.enabled=1 AND eb.user_id"
                + " IS NULL))))) AND  (NOT EXISTS(SELECT 1 FROM community_article_access ac WHERE"
                + " ac.news_id=n.id AND ac.visibility&lt;&gt;'PUBLIC') OR (SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)=#{viewer} OR EXISTS(SELECT 1"
                + " FROM community_article_access ac JOIN community_article_reader ar ON"
                + " ar.news_id=ac.news_id WHERE ac.news_id=n.id AND ac.visibility='FRIENDS' AND"
                + " ar.user_id=#{viewer} AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=#{viewer} AND f.target_key=CONCAT('u',(SELECT va.author_user_id"
                + " FROM community_article va WHERE va.news_id=n.id)) AND f.following=1) AND"
                + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=(SELECT"
                + " va.author_user_id FROM community_article va WHERE va.news_id=n.id) AND"
                + " f.target_key=CONCAT('u',#{viewer}) AND f.following=1)))  AND NOT EXISTS(SELECT"
                + " 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                + " ah.user_id=#{viewer})  AND LOCATE(#{keyword},n.title)>0 ORDER BY a.gmt_modified"
                + " DESC,n.id DESC LIMIT #{limit} OFFSET #{offset}</script> ")
    List<Map<String, Object>> selectActionArticles(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset,
            @Param("liked") boolean liked);
}
