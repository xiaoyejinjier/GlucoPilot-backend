package com.threadfit.glucopilot.community.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.article.entity.CommunityArticle;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 文章读写接口：SQL见resources同包路径的CommunityPublicationMapper.xml，行锁共享Service事务。 */
@Mapper
public interface CommunityPublicationMapper extends BaseMapper<CommunityArticle> {

    List<Long> selectActiveUser(@Param("id") long id, @Param("lock") boolean lock);

    List<Map<String, Object>> selectVisibleTags(@Param("viewer") Long viewer);

    List<Map<String, Object>> selectArticleTags(@Param("id") long id);

    List<String> lockEnabledTagCodes();

    List<String> lockDraftTagCodes();

    Long countDisabledTagName(@Param("name") String name);

    List<Map<String, Object>> selectCustomTag(@Param("name") String name);

    int insertCustomTag(@Param("code") String code, @Param("name") String name);

    Integer selectTagEnabled(@Param("code") String code);

    int deleteArticleTags(@Param("id") long id);

    int insertArticleTag(@Param("id") long id, @Param("code") String code);

    Long countOwnedImage(@Param("mediaId") long mediaId, @Param("author") long author);

    Long countOwnedVideo(@Param("mediaId") long mediaId, @Param("author") long author);

    int deleteArticleMedia(@Param("id") long id);

    int insertArticleMedia(
            @Param("id") long id, @Param("mediaId") long mediaId, @Param("sortNo") int sortNo);

    List<Long> selectSubmittedArticleIds(
            @Param("author") long author, @Param("clientRequestId") String clientRequestId);

    Long countRecentArticles(@Param("author") long author, @Param("since") LocalDateTime since);

    List<Long> selectBloggerIds(@Param("author") long author);

    int insertUserBlogger(@Param("author") long author);

    Long selectUserBlogger(@Param("author") long author);

    int insertSubmission(
            @Param("newsId") long newsId,
            @Param("author") long author,
            @Param("clientRequestId") String clientRequestId);

    int updateArticleContent(
            @Param("title") String title,
            @Param("titlePinyin") String titlePinyin,
            @Param("titlePinyinInitials") String titlePinyinInitials,
            @Param("summary") String summary,
            @Param("body") String body,
            @Param("contentType") int contentType,
            @Param("id") long id);

    int resetArticleReview(@Param("id") long id);

    List<Map<String, Object>> selectAuthorTags(
            @Param("viewer") long viewer, @Param("includeDisabled") int includeDisabled);

    Long countArticles(@Param("query") CommunityArticleQuery query);

    // 推荐列表：XML引用推荐Mapper的评分片段，管理端与小程序使用同一公式。
    List<Map<String, Object>> selectArticles(
            @Param("query") CommunityArticleQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /** 热榜等：published+readable + newsIds IN，无推荐排序。 */
    List<Map<String, Object>> selectArticlesByIds(@Param("query") CommunityArticleQuery query);

    List<Map<String, Object>> selectArticle(@Param("id") long id);

    Map<String, Object> selectPublicArticle(@Param("id") long id);

    Long countPublicArticle(@Param("id") long id);

    List<Map<String, Object>> selectArticleActions(
            @Param("id") long id, @Param("viewer") Long viewer);

    List<Map<String, Object>> selectAuthorProfile(@Param("author") Long author);

    List<Map<String, Object>> lockSubmission(@Param("id") long id);

    int updateReview(
            @Param("state") String state,
            @Param("note") String note,
            @Param("admin") long admin,
            @Param("id") long id);

    int updatePublicationStatus(@Param("status") int status, @Param("id") long id);

    int retryReview(@Param("id") long id);

    int insertModerationLog(
            @Param("type") String type,
            @Param("targetId") long targetId,
            @Param("revision") Integer revision,
            @Param("actorType") String actorType,
            @Param("actorId") Long actorId,
            @Param("decision") String decision,
            @Param("note") String note);
}
