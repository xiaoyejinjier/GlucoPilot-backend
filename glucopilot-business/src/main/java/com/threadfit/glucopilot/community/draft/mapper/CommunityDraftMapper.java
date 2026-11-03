package com.threadfit.glucopilot.community.draft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.draft.entity.CommunityArticleDraft;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityDraftMapper extends BaseMapper<CommunityArticleDraft> {

    @Select(
            "<script>SELECT id FROM user_account WHERE id=#{user} AND is_deleted=0 AND"
                    + " account_status=1 <if test=\"lock\"> FOR UPDATE</if></script> ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> selectActiveUser(@Param("user") long user, @Param("lock") boolean lock);

    @Select(
            "<script>SELECT * FROM community_article_draft WHERE id=#{id} AND user_id=#{user} <if"
                    + " test=\"!closed\"> AND is_deleted=0</if><if test=\"lock\"> FOR"
                    + " UPDATE</if></script> ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> selectOwnedDraft(
            @Param("id") long id,
            @Param("user") long user,
            @Param("closed") boolean closed,
            @Param("lock") boolean lock);

    @Select("SELECT COUNT(*) FROM community_article_draft WHERE user_id=#{user} AND is_deleted=0 ")
    Long countDrafts(@Param("user") long user);

    @Select(
            "SELECT * FROM community_article_draft WHERE user_id=#{user} AND is_deleted=0 ORDER BY"
                    + " updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset} ")
    List<Map<String, Object>> selectDrafts(
            @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

    @Select(
            "SELECT * FROM community_article_draft WHERE user_id=#{user} AND"
                    + " client_request_id=#{clientRequestId} ")
    List<Map<String, Object>> selectByRequest(
            @Param("user") long user, @Param("clientRequestId") String clientRequestId);

    @Select("SELECT COUNT(*) FROM community_article_draft WHERE user_id=#{user} AND is_deleted=0 ")
    Long countDraftsForCreate(@Param("user") long user);

    @Insert(
            "INSERT INTO"
                + " community_article_draft(user_id,title,body,tag_codes,custom_tags,image_ids,source_article_id,source_revision,client_request_id,last_request_id,video_ids,visibility,viewer_ids)"
                + " VALUES(#{user},#{title},#{body},#{tagCodes},#{customTags},#{imageIds},#{sourceArticleId},#{sourceRevision},#{clientRequestId},#{lastRequestId},#{videoIds},#{visibility},#{viewerIds})"
                + " ")
    int insertDraft(
            @Param("user") long user,
            @Param("title") String title,
            @Param("body") String body,
            @Param("tagCodes") String tagCodes,
            @Param("customTags") String customTags,
            @Param("imageIds") String imageIds,
            @Param("sourceArticleId") Long sourceArticleId,
            @Param("sourceRevision") Integer sourceRevision,
            @Param("clientRequestId") String clientRequestId,
            @Param("lastRequestId") String lastRequestId,
            @Param("videoIds") String videoIds,
            @Param("visibility") String visibility,
            @Param("viewerIds") String viewerIds);

    @Select(
            "SELECT * FROM community_article_draft WHERE user_id=#{user} AND"
                    + " client_request_id=#{clientRequestId} ")
    Map<String, Object> selectCreatedDraft(
            @Param("user") long user, @Param("clientRequestId") String clientRequestId);

    @Update(
            "UPDATE community_article_draft SET"
                + " title=#{title},body=#{body},tag_codes=#{tagCodes},custom_tags=#{customTags},image_ids=#{imageIds},video_ids=#{videoIds},visibility=#{visibility},viewer_ids=#{viewerIds},revision=revision+1,last_request_id=#{lastRequestId},updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} ")
    int updateDraft(
            @Param("title") String title,
            @Param("body") String body,
            @Param("tagCodes") String tagCodes,
            @Param("customTags") String customTags,
            @Param("imageIds") String imageIds,
            @Param("videoIds") String videoIds,
            @Param("visibility") String visibility,
            @Param("viewerIds") String viewerIds,
            @Param("lastRequestId") String lastRequestId,
            @Param("id") long id);

    @Update(
            "UPDATE community_article_draft SET is_deleted=1,updated_at=CURRENT_TIMESTAMP WHERE"
                    + " id=#{id} ")
    int softDeleteDraft(@Param("id") long id);

    @Update(
            "UPDATE community_article_draft SET"
                + " published_news_id=#{newsId},publish_request_id=#{publishRequestId},is_deleted=1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=#{id} ")
    int markPublished(
            @Param("newsId") long newsId,
            @Param("publishRequestId") String publishRequestId,
            @Param("id") long id);
}
