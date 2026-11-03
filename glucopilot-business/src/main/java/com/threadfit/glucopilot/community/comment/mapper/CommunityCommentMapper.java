package com.threadfit.glucopilot.community.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.comment.entity.CommunityComment;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityCommentMapper extends BaseMapper<CommunityComment> {

    @Select(
            "SELECT COUNT(*) FROM community_comment c WHERE c.news_id=#{newsId} AND  c.is_deleted=0"
                + " AND c.is_hidden=0 AND EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id"
                + " AND u.is_deleted=0 AND u.account_status=1) AND (c.root_comment_id IS NULL OR"
                + " EXISTS(SELECT 1 FROM community_comment r WHERE r.id=c.root_comment_id AND"
                + " r.news_id=c.news_id AND r.root_comment_id IS NULL AND r.is_deleted=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1))) ")
    Long countVisibleComments(@Param("newsId") long newsId);

    @Select(
            "SELECT c.user_id FROM community_comment c WHERE c.id=#{id} AND c.news_id=#{newsId} AND"
                + "  c.is_deleted=0 AND c.is_hidden=0 AND EXISTS(SELECT 1 FROM user_account u WHERE"
                + " u.id=c.user_id AND u.is_deleted=0 AND u.account_status=1) AND"
                + " (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM community_comment r WHERE"
                + " r.id=c.root_comment_id AND r.news_id=c.news_id AND r.root_comment_id IS NULL"
                + " AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM user_account u WHERE u.id=r.user_id"
                + " AND u.is_deleted=0 AND u.account_status=1))) ")
    List<Long> selectReplyTargetAuthors(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "SELECT c.* FROM community_comment c WHERE c.news_id=#{newsId} AND c.root_comment_id IS"
                + " NULL AND  c.is_deleted=0 AND c.is_hidden=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND u.account_status=1)"
                + " AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM community_comment r"
                + " WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND r.root_comment_id IS"
                + " NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM user_account u WHERE"
                + " u.id=r.user_id AND u.is_deleted=0 AND u.account_status=1)))  ORDER BY c.id DESC"
                + " LIMIT #{limit} OFFSET #{offset} ")
    List<CommunityComment> selectRootComments(
            @Param("newsId") long newsId, @Param("limit") int limit, @Param("offset") long offset);

    @Select(
            "SELECT COUNT(*) FROM community_comment c WHERE c.news_id=#{newsId} AND"
                + " c.root_comment_id IS NULL AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1))) ")
    Long countRootComments(@Param("newsId") long newsId);

    @Select(
            "SELECT COUNT(*) FROM community_comment c LEFT JOIN cms_news n ON n.id=c.news_id WHERE"
                    + " c.user_id=#{viewer} AND c.is_deleted=0 ")
    Long countOwnComments(@Param("viewer") long viewer);

    @Select(
            "SELECT"
                + " c.id,c.news_id,c.content,c.created_at,c.root_comment_id,c.reply_to_comment_id,c.is_hidden,n.title,n.is_deleted"
                + " news_deleted,n.publish_status,(SELECT COUNT(*) FROM community_comment r WHERE"
                + " r.root_comment_id=c.id AND r.is_deleted=0 AND r.is_hidden=0)"
                + " reply_count,(SELECT COUNT(*) FROM community_comment_like l WHERE"
                + " l.comment_id=c.id AND l.liked=1) like_count FROM community_comment c LEFT JOIN"
                + " cms_news n ON n.id=c.news_id WHERE c.user_id=#{viewer} AND c.is_deleted=0 "
                + " ORDER BY c.id DESC LIMIT #{limit} OFFSET #{offset} ")
    List<Map<String, Object>> selectOwnComments(
            @Param("viewer") long viewer, @Param("limit") int limit, @Param("offset") long offset);

    @Select(
            "SELECT c.* FROM community_comment c WHERE c.id=#{id} AND c.news_id=#{newsId} AND "
                + " c.is_deleted=0 AND c.is_hidden=0 AND EXISTS(SELECT 1 FROM user_account u WHERE"
                + " u.id=c.user_id AND u.is_deleted=0 AND u.account_status=1) AND"
                + " (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM community_comment r WHERE"
                + " r.id=c.root_comment_id AND r.news_id=c.news_id AND r.root_comment_id IS NULL"
                + " AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM user_account u WHERE u.id=r.user_id"
                + " AND u.is_deleted=0 AND u.account_status=1))) ")
    List<CommunityComment> selectVisibleComment(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "SELECT c.* FROM community_comment c WHERE c.id=#{id} AND c.news_id=#{newsId} AND"
                + " c.root_comment_id IS NULL AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1))) ")
    List<CommunityComment> selectVisibleRoot(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "SELECT COUNT(*) FROM community_comment c WHERE c.news_id=#{newsId} AND"
                + " c.root_comment_id=#{root} AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1))) ")
    Long countReplies(@Param("newsId") long newsId, @Param("root") long root);

    @Select(
            "SELECT COUNT(*) FROM community_comment c WHERE c.id=#{id} AND c.news_id=#{newsId} AND"
                + " c.root_comment_id IS NULL AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1))) ")
    Long countVisibleRoot(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "<script>SELECT c.* FROM community_comment c WHERE c.news_id=#{newsId} AND"
                + " c.root_comment_id=#{root} AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1)))  <if test=\"afterId != null\"> AND c.id &gt;"
                + " #{afterId}</if> ORDER BY c.id ASC LIMIT #{limit}</script> ")
    List<CommunityComment> selectRepliesAfter(
            @Param("newsId") long newsId,
            @Param("root") long root,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    @Select(
            "SELECT c.root_comment_id FROM community_comment c WHERE c.id=#{id} AND"
                + " c.news_id=#{newsId} AND  c.is_deleted=0 AND c.is_hidden=0 AND EXISTS(SELECT 1"
                + " FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1))) ")
    List<Long> selectTargetRootIds(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "SELECT c.id FROM community_comment c WHERE c.id=#{root} AND c.news_id=#{newsId} AND"
                + " c.root_comment_id IS NULL AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1)))  FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockRootComment(@Param("root") long root, @Param("newsId") long newsId);

    @Select(
            "SELECT c.id FROM community_comment c WHERE c.id=#{id} AND c.news_id=#{newsId} AND"
                + " c.root_comment_id=#{root} AND  c.is_deleted=0 AND c.is_hidden=0 AND"
                + " EXISTS(SELECT 1 FROM user_account u WHERE u.id=c.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1) AND (c.root_comment_id IS NULL OR EXISTS(SELECT 1 FROM"
                + " community_comment r WHERE r.id=c.root_comment_id AND r.news_id=c.news_id AND"
                + " r.root_comment_id IS NULL AND r.is_deleted=0 AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE u.id=r.user_id AND u.is_deleted=0 AND"
                + " u.account_status=1)))  FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockReplyComment(
            @Param("id") long id, @Param("newsId") long newsId, @Param("root") long root);

    @Select(
            "SELECT id FROM community_comment WHERE user_id=#{viewer} AND"
                    + " client_request_id=#{clientRequestId} ")
    List<Long> selectCommentRequestIds(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    @Insert(
            "INSERT INTO"
                + " community_comment(news_id,user_id,content,client_request_id,reply_to_comment_id,root_comment_id)"
                + " VALUES(#{newsId},#{viewer},#{content},#{clientRequestId},#{target},#{root}) ")
    int insertComment(
            @Param("newsId") long newsId,
            @Param("viewer") long viewer,
            @Param("content") String content,
            @Param("clientRequestId") String clientRequestId,
            @Param("target") Long target,
            @Param("root") Long root);

    @Select(
            "SELECT id,news_id,content,is_deleted,reply_to_comment_id FROM community_comment WHERE"
                    + " user_id=#{viewer} AND client_request_id=#{clientRequestId} FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<CommunityComment> lockCommentRequest(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    @Select(
            "SELECT c.* FROM community_comment c WHERE c.id=#{id} AND c.news_id=#{newsId} AND"
                    + " c.is_deleted=0 ")
    List<CommunityComment> selectSavedComment(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "SELECT user_id FROM community_comment WHERE id=#{id} AND news_id=#{newsId} AND"
                    + " is_deleted=0 ")
    List<Long> selectCommentAuthors(@Param("id") long id, @Param("newsId") long newsId);

    @Select(
            "SELECT COUNT(*) FROM community_comment WHERE id=#{id} AND news_id=#{newsId} AND"
                    + " user_id=#{viewer} ")
    Long countOwnComment(
            @Param("id") long id, @Param("newsId") long newsId, @Param("viewer") long viewer);

    @Update(
            "UPDATE community_comment SET is_deleted=1 WHERE id=#{id} AND news_id=#{newsId} AND"
                    + " user_id=#{viewer} ")
    int softDeleteOwnComment(
            @Param("id") long id, @Param("newsId") long newsId, @Param("viewer") long viewer);
}
