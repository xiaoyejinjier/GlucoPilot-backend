package com.threadfit.glucopilot.community.report.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityReportManagementMapper {

    @Select(
            "<script>SELECT id FROM user_account WHERE id=#{id} AND is_deleted=0 AND"
                    + " account_status=1 <if test=\"lock\"> FOR UPDATE</if></script> ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> selectActiveUser(@Param("id") long id, @Param("lock") boolean lock);

    @Select(
            "SELECT * FROM community_report WHERE reporter_id=#{viewer} AND"
                    + " client_request_id=#{clientRequestId} ")
    List<Map<String, Object>> selectReportByRequest(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    @Select(
            "SELECT COUNT(*) FROM community_comment c JOIN cms_news n ON n.id=c.news_id WHERE"
                    + " c.id=#{id} AND c.is_deleted=0 AND n.is_deleted=0 AND n.publish_status=1 ")
    Long countReportableComment(@Param("id") long id);

    @Select(
            "SELECT COUNT(*) FROM community_message WHERE id=#{id} AND (sender_id=#{sender} OR"
                    + " recipient_id=#{recipient}) ")
    Long countReportableMessage(
            @Param("id") long id, @Param("sender") long sender, @Param("recipient") long recipient);

    @Select("SELECT COUNT(*) FROM community_report WHERE reporter_id=#{viewer} AND state='OPEN' ")
    Long countOpenReports(@Param("viewer") long viewer);

    @Select("SELECT news_id FROM community_comment WHERE id=#{id} ")
    Long selectCommentArticle(@Param("id") long id);

    @Insert(
            "INSERT INTO"
                + " community_report(reporter_id,target_type,target_id,reason,details,client_request_id,target_title)"
                + " VALUES(#{viewer},#{type},#{target},#{reason},#{details},#{clientRequestId},#{title})"
                + " ")
    int insertReport(
            @Param("viewer") long viewer,
            @Param("type") String type,
            @Param("target") long target,
            @Param("reason") String reason,
            @Param("details") String details,
            @Param("clientRequestId") String clientRequestId,
            @Param("title") String title);

    @Select(
            "SELECT id,state FROM community_report WHERE reporter_id=#{viewer} AND"
                    + " client_request_id=#{clientRequestId} ")
    Map<String, Object> selectSavedReport(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    @Select(
            "<script>SELECT COUNT(*) FROM community_report WHERE 1=1 <if test='state != null and"
                    + " state != &quot;&quot;'> AND state=#{state}</if></script> ")
    Long countReports(@Param("state") String state);

    @Select(
            "<script>SELECT * FROM community_report WHERE 1=1 <if test='state != null and state !="
                + " &quot;&quot;'> AND state=#{state}</if> ORDER BY id DESC LIMIT #{limit} OFFSET"
                + " #{offset}</script> ")
    List<Map<String, Object>> selectReports(
            @Param("state") String state, @Param("limit") int limit, @Param("offset") long offset);

    @Select(
            "<script>SELECT <choose><when test='type == &quot;ARTICLE_TITLE&quot;'>title FROM"
                + " cms_news</when><when test='type == &quot;ARTICLE&quot;'>CONCAT(title,'\\n"
                + "',content_body) FROM cms_news</when><when test='type =="
                + " &quot;COMMENT&quot;'>content FROM community_comment</when><otherwise>content"
                + " FROM community_message</otherwise></choose> WHERE id=#{id}</script> ")
    List<String> selectReportContext(@Param("type") String type, @Param("id") long id);

    @Select("SELECT * FROM community_report WHERE id=#{id} FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> lockReport(@Param("id") long id);

    @Update(
            "UPDATE cms_news SET publish_status=2,gmt_modified=CURRENT_TIMESTAMP WHERE id=#{id} AND"
                    + " is_deleted=0 ")
    int hideArticle(@Param("id") long id);

    @Update(
            "UPDATE community_article SET state='HIDDEN',updated_at=CURRENT_TIMESTAMP WHERE"
                    + " news_id=#{id} ")
    int hideSubmission(@Param("id") long id);

    @Update("UPDATE community_comment SET is_deleted=1 WHERE id=#{id} ")
    int deleteReportedComment(@Param("id") long id);

    @Update(
            "UPDATE community_report SET"
                + " state=#{state},resolution=#{action},resolution_note=#{note},resolved_by=#{admin},resolved_at=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} ")
    int resolveReport(
            @Param("state") String state,
            @Param("action") String action,
            @Param("note") String note,
            @Param("admin") long admin,
            @Param("id") long id);

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
