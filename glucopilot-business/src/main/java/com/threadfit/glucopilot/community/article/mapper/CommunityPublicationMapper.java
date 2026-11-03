package com.threadfit.glucopilot.community.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.article.entity.CommunityArticle;
import com.threadfit.glucopilot.community.recommendation.mapper.CommunityRecommendationSql;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityPublicationMapper extends BaseMapper<CommunityArticle> {

    @Select(
            "<script>SELECT id FROM user_account WHERE id=#{id} AND is_deleted=0 AND"
                    + " account_status=1 <if test=\"lock\"> FOR UPDATE</if></script> ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> selectActiveUser(@Param("id") long id, @Param("lock") boolean lock);

    @Select(
            "SELECT t.code,t.name FROM community_tag t WHERE t.enabled=1 AND"
                + " (LEFT(t.code,7)<>'custom_' OR EXISTS(SELECT 1 FROM community_article_tag atg"
                + " JOIN cms_news n ON n.id=atg.news_id LEFT JOIN community_article a ON"
                + " a.news_id=n.id WHERE atg.tag_id=t.id AND n.is_deleted=0 AND n.publish_status=1"
                + " AND n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR"
                + " a.state='PUBLISHED') AND  (NOT EXISTS(SELECT 1 FROM community_article_access ac"
                + " WHERE ac.news_id=n.id AND ac.visibility<>'PUBLIC') OR (SELECT va.author_user_id"
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
                + " ah.user_id=#{viewer}) )) ORDER BY t.sort_no,t.id ")
    List<Map<String, Object>> selectVisibleTags(@Param("viewer") Long viewer);

    @Select(
            "SELECT t.code,t.name FROM community_article_tag a JOIN community_tag t ON"
                + " t.id=a.tag_id WHERE a.news_id=#{id} AND t.enabled=1 ORDER BY t.sort_no,t.id ")
    List<Map<String, Object>> selectArticleTags(@Param("id") long id);

    @Select("SELECT code FROM community_tag WHERE enabled=1 ORDER BY id FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<String> lockEnabledTagCodes();

    @Select("SELECT code FROM community_tag WHERE enabled=1 ORDER BY id FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<String> lockDraftTagCodes();

    @Select("SELECT COUNT(*) FROM community_tag WHERE LOWER(name)=LOWER(#{name}) AND enabled=0 ")
    Long countDisabledTagName(@Param("name") String name);

    @Select("SELECT code,enabled FROM community_tag WHERE name=#{name} ORDER BY id LIMIT 1 ")
    List<Map<String, Object>> selectCustomTag(@Param("name") String name);

    @Insert(
            "INSERT INTO community_tag(code,name,sort_no) VALUES(#{code},#{name},100) ON DUPLICATE"
                    + " KEY UPDATE code=code ")
    int insertCustomTag(@Param("code") String code, @Param("name") String name);

    @Select("SELECT enabled FROM community_tag WHERE code=#{code} ")
    Integer selectTagEnabled(@Param("code") String code);

    @Delete("DELETE FROM community_article_tag WHERE news_id=#{id} ")
    int deleteArticleTags(@Param("id") long id);

    @Insert(
            "INSERT INTO community_article_tag(news_id,tag_id) SELECT #{id},id FROM community_tag"
                    + " WHERE code=#{code} AND enabled=1 ")
    int insertArticleTag(@Param("id") long id, @Param("code") String code);

    @Select(
            "SELECT COUNT(*) FROM media_file WHERE id=#{mediaId} AND user_id=#{author} AND ops_id"
                + " IS NULL AND biz_type=7 AND file_status=1 AND is_deleted=0 AND size_bytes>0 AND"
                + " size_bytes<=5242880 AND mime_type IN"
                + " ('image/jpeg','image/png','image/webp','image/gif') ")
    Long countOwnedImage(@Param("mediaId") long mediaId, @Param("author") long author);

    @Select(
            "SELECT COUNT(*) FROM media_file WHERE id=#{mediaId} AND user_id=#{author} AND ops_id"
                + " IS NULL AND biz_type=8 AND file_status=1 AND is_deleted=0 AND size_bytes>0 AND"
                + " size_bytes<=20971520 AND mime_type='video/mp4' ")
    Long countOwnedVideo(@Param("mediaId") long mediaId, @Param("author") long author);

    @Delete("DELETE FROM community_article_image WHERE news_id=#{id} ")
    int deleteArticleMedia(@Param("id") long id);

    @Insert(
            "INSERT INTO community_article_image(news_id,media_id,sort_no)"
                    + " VALUES(#{id},#{mediaId},#{sortNo}) ")
    int insertArticleMedia(
            @Param("id") long id, @Param("mediaId") long mediaId, @Param("sortNo") int sortNo);

    @Select(
            "SELECT news_id FROM community_article WHERE author_user_id=#{author} AND"
                    + " client_request_id=#{clientRequestId} ")
    List<Long> selectSubmittedArticleIds(
            @Param("author") long author, @Param("clientRequestId") String clientRequestId);

    @Select(
            "SELECT COUNT(*) FROM community_article WHERE author_user_id=#{author} AND"
                    + " created_at>#{since} ")
    Long countRecentArticles(@Param("author") long author, @Param("since") LocalDateTime since);

    @Select("SELECT id FROM community_blogger WHERE user_id=#{author} ORDER BY id LIMIT 1 ")
    List<Long> selectBloggerIds(@Param("author") long author);

    @Insert("INSERT INTO community_blogger(user_id,nickname,bio) VALUES(#{author},'社区用户','') ")
    int insertUserBlogger(@Param("author") long author);

    @Select("SELECT id FROM community_blogger WHERE user_id=#{author} ORDER BY id LIMIT 1 ")
    Long selectUserBlogger(@Param("author") long author);

    @Insert(
            "INSERT INTO community_article(news_id,author_user_id,client_request_id)"
                    + " VALUES(#{newsId},#{author},#{clientRequestId}) ")
    int insertSubmission(
            @Param("newsId") long newsId,
            @Param("author") long author,
            @Param("clientRequestId") String clientRequestId);

    @Update(
            "UPDATE cms_news SET"
                + " title=#{title},summary_text=#{summary},content_body=#{body},content_type=#{contentType},publish_status=0,gmt_modified=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} AND is_deleted=0 ")
    int updateArticleContent(
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("body") String body,
            @Param("contentType") int contentType,
            @Param("id") long id);

    @Update(
            "UPDATE community_article SET"
                + " state='AI_PENDING',revision=revision+1,ai_verdict='PENDING',ai_reason='',ai_model='',ai_checked_at=NULL,review_note='',reviewed_by=NULL,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE news_id=#{id} ")
    int resetArticleReview(@Param("id") long id);

    @Select(
            "SELECT DISTINCT t.code,t.name,t.enabled FROM community_article_tag atg JOIN"
                    + " community_tag t ON t.id=atg.tag_id JOIN community_article a ON"
                    + " a.news_id=atg.news_id JOIN cms_news n ON n.id=a.news_id WHERE"
                    + " a.author_user_id=#{viewer} AND n.is_deleted=0 AND (t.enabled=1 OR"
                    + " #{includeDisabled}=1) ORDER BY t.name LIMIT 100 ")
    List<Map<String, Object>> selectAuthorTags(
            @Param("viewer") long viewer, @Param("includeDisabled") int includeDisabled);

    @Select(
            "<script>SELECT COUNT(*) FROM cms_news n LEFT JOIN community_article a ON"
                + " a.news_id=n.id WHERE n.is_deleted=0 <if test='query.published'> AND"
                + " n.is_deleted=0 AND n.publish_status=1 AND n.publish_at&lt;=CURRENT_TIMESTAMP"
                + " AND (n.community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa LEFT"
                + " JOIN user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND"
                + " au.account_status=1 WHERE pa.news_id=n.id AND pa.state='PUBLISHED' AND (au.id"
                + " IS NOT NULL OR (pa.author_user_id IS NULL AND EXISTS(SELECT 1 FROM"
                + " community_blogger eb WHERE eb.id=n.blogger_id AND eb.enabled=1 AND eb.user_id"
                + " IS NULL)))))</if> <if test='query.readable'> AND  (NOT EXISTS(SELECT 1 FROM"
                + " community_article_access ac WHERE ac.news_id=n.id AND"
                + " ac.visibility&lt;&gt;'PUBLIC') OR (SELECT va.author_user_id FROM"
                + " community_article va WHERE va.news_id=n.id)=#{query.viewer} OR EXISTS(SELECT 1"
                + " FROM community_article_access ac JOIN community_article_reader ar ON"
                + " ar.news_id=ac.news_id WHERE ac.news_id=n.id AND ac.visibility='FRIENDS' AND"
                + " ar.user_id=#{query.viewer} AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=#{query.viewer} AND f.target_key=CONCAT('u',(SELECT"
                + " va.author_user_id FROM community_article va WHERE va.news_id=n.id)) AND"
                + " f.following=1) AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                + " f.follower_id=(SELECT va.author_user_id FROM community_article va WHERE"
                + " va.news_id=n.id) AND f.target_key=CONCAT('u',#{query.viewer}) AND"
                + " f.following=1)))  AND NOT EXISTS(SELECT 1 FROM community_article_hidden ah"
                + " WHERE ah.news_id=n.id AND ah.user_id=#{query.viewer}) </if> <if test='query.tag"
                + " != null and query.tag != \"\"'> AND EXISTS(SELECT 1 FROM community_article_tag"
                + " atg JOIN community_tag t ON t.id=atg.tag_id WHERE atg.news_id=n.id AND"
                + " t.enabled=1 <choose><when test='query.tag == \"other\"'> AND (t.code='other' OR"
                + " LEFT(t.code,7)='custom_')</when><otherwise> AND"
                + " t.code=#{query.tag}</otherwise></choose>)</if> <if test='query.following'> AND"
                + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=#{query.viewer} AND"
                + " f.following=1 AND (f.target_key=CONCAT('u',a.author_user_id) OR"
                + " f.target_key=CONCAT('b',n.blogger_id) OR f.target_key=CONCAT('u',(SELECT"
                + " b.user_id FROM community_blogger b WHERE b.id=n.blogger_id))))</if> <if"
                + " test='query.authorUser != null'> AND (a.author_user_id=#{query.authorUser} OR"
                + " EXISTS(SELECT 1 FROM community_blogger b WHERE b.id=n.blogger_id AND"
                + " b.user_id=#{query.authorUser}))</if> <if test='query.blogger != null'> AND"
                + " n.blogger_id=#{query.blogger}</if> <if test='query.mine != null'> AND"
                + " a.author_user_id=#{query.mine}</if> <if test='query.state != null and"
                + " query.state != \"\"'> AND (CASE WHEN n.community_managed=1 THEN"
                + " COALESCE(a.state,'AI_PENDING') ELSE CASE n.publish_status WHEN 1 THEN"
                + " 'PUBLISHED' WHEN 2 THEN 'HIDDEN' ELSE 'DRAFT' END END)=#{query.state}</if> <if"
                + " test='query.source != null and query.source != \"\"'> AND"
                + " n.community_managed=<choose><when test='query.source =="
                + " \"COMMUNITY\"'>1</when><otherwise>0</otherwise></choose></if> <if"
                + " test='query.keyword != null and query.keyword != \"\"'> AND"
                + " (LOCATE(#{query.keyword},n.title)>0 <if test='query.adminSearch'> OR"
                + " EXISTS(SELECT 1 FROM user_profile p WHERE p.user_id=a.author_user_id AND"
                + " p.is_deleted=0 AND LOCATE(#{query.keyword},p.nickname)>0) OR EXISTS(SELECT 1"
                + " FROM community_blogger b WHERE b.id=n.blogger_id AND a.author_user_id IS NULL"
                + " AND LOCATE(#{query.keyword},b.nickname)>0)</if>)</if></script> ")
    Long countArticles(@Param("query") CommunityArticleQuery query);

    @Select(
            "<script>SELECT n.*,a.author_user_id,CASE WHEN n.community_managed=1 THEN"
                + " COALESCE(a.state,'AI_PENDING') ELSE CASE n.publish_status WHEN 1 THEN"
                + " 'PUBLISHED' WHEN 2 THEN 'HIDDEN' ELSE 'DRAFT' END END"
                + " article_state,a.revision,a.ai_verdict,a.ai_reason,a.ai_model,a.review_note,COALESCE(a.created_at,n.gmt_create)"
                + " article_created_at "
                    + CommunityRecommendationSql.EXPLANATION_COLUMNS
                    + " FROM cms_news n LEFT JOIN community_article a ON a.news_id=n.id  WHERE"
                    + " n.is_deleted=0 <if test='query.published'> AND n.is_deleted=0 AND"
                    + " n.publish_status=1 AND n.publish_at&lt;=CURRENT_TIMESTAMP AND"
                    + " (n.community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa LEFT"
                    + " JOIN user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND"
                    + " au.account_status=1 WHERE pa.news_id=n.id AND pa.state='PUBLISHED' AND"
                    + " (au.id IS NOT NULL OR (pa.author_user_id IS NULL AND EXISTS(SELECT 1 FROM"
                    + " community_blogger eb WHERE eb.id=n.blogger_id AND eb.enabled=1 AND"
                    + " eb.user_id IS NULL)))))</if> <if test='query.readable'> AND  (NOT"
                    + " EXISTS(SELECT 1 FROM community_article_access ac WHERE ac.news_id=n.id AND"
                    + " ac.visibility&lt;&gt;'PUBLIC') OR (SELECT va.author_user_id FROM"
                    + " community_article va WHERE va.news_id=n.id)=#{query.viewer} OR"
                    + " EXISTS(SELECT 1 FROM community_article_access ac JOIN"
                    + " community_article_reader ar ON ar.news_id=ac.news_id WHERE ac.news_id=n.id"
                    + " AND ac.visibility='FRIENDS' AND ar.user_id=#{query.viewer} AND"
                    + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=#{query.viewer}"
                    + " AND f.target_key=CONCAT('u',(SELECT va.author_user_id FROM"
                    + " community_article va WHERE va.news_id=n.id)) AND f.following=1) AND"
                    + " EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=(SELECT"
                    + " va.author_user_id FROM community_article va WHERE va.news_id=n.id) AND"
                    + " f.target_key=CONCAT('u',#{query.viewer}) AND f.following=1)))  AND NOT"
                    + " EXISTS(SELECT 1 FROM community_article_hidden ah WHERE ah.news_id=n.id AND"
                    + " ah.user_id=#{query.viewer}) </if> <if test='query.tag != null and query.tag"
                    + " != \"\"'> AND EXISTS(SELECT 1 FROM community_article_tag atg JOIN"
                    + " community_tag t ON t.id=atg.tag_id WHERE atg.news_id=n.id AND t.enabled=1"
                    + " <choose><when test='query.tag == \"other\"'> AND (t.code='other' OR"
                    + " LEFT(t.code,7)='custom_')</when><otherwise> AND"
                    + " t.code=#{query.tag}</otherwise></choose>)</if> <if test='query.following'>"
                    + " AND EXISTS(SELECT 1 FROM community_follow f WHERE"
                    + " f.follower_id=#{query.viewer} AND f.following=1 AND"
                    + " (f.target_key=CONCAT('u',a.author_user_id) OR"
                    + " f.target_key=CONCAT('b',n.blogger_id) OR f.target_key=CONCAT('u',(SELECT"
                    + " b.user_id FROM community_blogger b WHERE b.id=n.blogger_id))))</if> <if"
                    + " test='query.authorUser != null'> AND (a.author_user_id=#{query.authorUser}"
                    + " OR EXISTS(SELECT 1 FROM community_blogger b WHERE b.id=n.blogger_id AND"
                    + " b.user_id=#{query.authorUser}))</if> <if test='query.blogger != null'> AND"
                    + " n.blogger_id=#{query.blogger}</if> <if test='query.mine != null'> AND"
                    + " a.author_user_id=#{query.mine}</if> <if test='query.state != null and"
                    + " query.state != \"\"'> AND (CASE WHEN n.community_managed=1 THEN"
                    + " COALESCE(a.state,'AI_PENDING') ELSE CASE n.publish_status WHEN 1 THEN"
                    + " 'PUBLISHED' WHEN 2 THEN 'HIDDEN' ELSE 'DRAFT' END END)=#{query.state}</if>"
                    + " <if test='query.source != null and query.source != \"\"'> AND"
                    + " n.community_managed=<choose><when test='query.source =="
                    + " \"COMMUNITY\"'>1</when><otherwise>0</otherwise></choose></if> <if"
                    + " test='query.keyword != null and query.keyword != \"\"'> AND"
                    + " (LOCATE(#{query.keyword},n.title)>0 <if test='query.adminSearch'> OR"
                    + " EXISTS(SELECT 1 FROM user_profile p WHERE p.user_id=a.author_user_id AND"
                    + " p.is_deleted=0 AND LOCATE(#{query.keyword},p.nickname)>0) OR EXISTS(SELECT"
                    + " 1 FROM community_blogger b WHERE b.id=n.blogger_id AND a.author_user_id IS"
                    + " NULL AND LOCATE(#{query.keyword},b.nickname)>0)</if>)</if> "
                    + CommunityRecommendationSql.ORDER_BY
                    + " LIMIT #{limit} OFFSET #{offset}</script> ")
    List<Map<String, Object>> selectArticles(
            @Param("query") CommunityArticleQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select(
            "SELECT n.*,a.author_user_id,CASE WHEN n.community_managed=1 THEN"
                + " COALESCE(a.state,'AI_PENDING') ELSE CASE n.publish_status WHEN 1 THEN"
                + " 'PUBLISHED' WHEN 2 THEN 'HIDDEN' ELSE 'DRAFT' END END"
                + " article_state,a.revision,a.ai_verdict,a.ai_reason,a.ai_model,a.review_note,COALESCE(a.created_at,n.gmt_create)"
                + " article_created_at FROM cms_news n LEFT JOIN community_article a ON"
                + " a.news_id=n.id  WHERE n.id=#{id} AND n.is_deleted=0 ")
    List<Map<String, Object>> selectArticle(@Param("id") long id);

    @Select(
            "SELECT n.*,a.author_user_id,CASE WHEN n.community_managed=1 THEN"
                + " COALESCE(a.state,'AI_PENDING') ELSE CASE n.publish_status WHEN 1 THEN"
                + " 'PUBLISHED' WHEN 2 THEN 'HIDDEN' ELSE 'DRAFT' END END"
                + " article_state,a.revision,a.ai_verdict,a.ai_reason,a.ai_model,a.review_note,COALESCE(a.created_at,n.gmt_create)"
                + " article_created_at FROM cms_news n LEFT JOIN community_article a ON"
                + " a.news_id=n.id  WHERE n.id=#{id} AND n.is_deleted=0 ")
    Map<String, Object> selectPublicArticle(@Param("id") long id);

    @Select(
            "SELECT COUNT(*) FROM cms_news n LEFT JOIN community_article a ON a.news_id=n.id WHERE"
                    + " n.id=#{id} AND n.is_deleted=0 AND n.publish_status=1 AND"
                    + " n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR"
                    + " a.state='PUBLISHED') ")
    Long countPublicArticle(@Param("id") long id);

    @Select(
            "SELECT is_liked,is_collected FROM news_user_action WHERE news_id=#{id} AND"
                    + " user_id=#{viewer} AND is_deleted=0 ")
    List<Map<String, Object>> selectArticleActions(
            @Param("id") long id, @Param("viewer") Long viewer);

    @Select(
            "SELECT nickname,avatar_url FROM user_profile WHERE user_id=#{author} AND is_deleted=0"
                    + " ")
    List<Map<String, Object>> selectAuthorProfile(@Param("author") Long author);

    @Select(
            "SELECT a.* FROM community_article a JOIN cms_news n ON n.id=a.news_id WHERE"
                    + " a.news_id=#{id} AND n.is_deleted=0 FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> lockSubmission(@Param("id") long id);

    @Update(
            "UPDATE community_article SET"
                + " state=#{state},review_note=#{note},reviewed_by=#{admin},reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                + " WHERE news_id=#{id} ")
    int updateReview(
            @Param("state") String state,
            @Param("note") String note,
            @Param("admin") long admin,
            @Param("id") long id);

    @Update(
            "UPDATE cms_news SET"
                + " publish_status=#{status},publish_at=CURRENT_TIMESTAMP,gmt_modified=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} ")
    int updatePublicationStatus(@Param("status") int status, @Param("id") long id);

    @Update(
            "UPDATE community_article SET"
                    + " state='AI_PENDING',ai_verdict='PENDING',updated_at=CURRENT_TIMESTAMP WHERE"
                    + " news_id=#{id} ")
    int retryReview(@Param("id") long id);

    @Insert(
            "INSERT INTO"
                + " community_moderation_log(target_type,target_id,revision,actor_type,actor_id,decision,note)"
                + " VALUES(#{type},#{targetId},#{revision},#{actorType},#{actorId},#{decision},#{note})"
                + " ")
    int insertModerationLog(
            @Param("type") String type,
            @Param("targetId") long targetId,
            @Param("revision") Integer revision,
            @Param("actorType") String actorType,
            @Param("actorId") Long actorId,
            @Param("decision") String decision,
            @Param("note") String note);
}
