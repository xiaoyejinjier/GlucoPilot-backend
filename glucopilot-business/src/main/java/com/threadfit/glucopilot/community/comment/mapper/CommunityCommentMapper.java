package com.threadfit.glucopilot.community.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.comment.entity.CommunityComment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.*;

/** SQL位于resources/com/threadfit/glucopilot/community/comment/mapper/CommunityCommentMapper.xml。 */
@Mapper
public interface CommunityCommentMapper extends BaseMapper<CommunityComment> {

    Long countVisibleComments(@Param("newsId") long newsId);

    List<Long> selectReplyTargetAuthors(@Param("id") long id, @Param("newsId") long newsId);

    List<CommunityComment> selectRootComments(
            @Param("newsId") long newsId, @Param("limit") int limit, @Param("offset") long offset);

    Long countRootComments(@Param("newsId") long newsId);

    Long countOwnComments(@Param("viewer") long viewer);

    List<Map<String, Object>> selectOwnComments(
            @Param("viewer") long viewer, @Param("limit") int limit, @Param("offset") long offset);

    List<CommunityComment> selectVisibleComment(@Param("id") long id, @Param("newsId") long newsId);

    List<CommunityComment> selectVisibleRoot(@Param("id") long id, @Param("newsId") long newsId);

    Long countReplies(@Param("newsId") long newsId, @Param("root") long root);

    Long countVisibleRoot(@Param("id") long id, @Param("newsId") long newsId);

    List<CommunityComment> selectRepliesAfter(
            @Param("newsId") long newsId,
            @Param("root") long root,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<Long> selectTargetRootIds(@Param("id") long id, @Param("newsId") long newsId);

    List<Long> lockRootComment(@Param("root") long root, @Param("newsId") long newsId);

    List<Long> lockReplyComment(
            @Param("id") long id, @Param("newsId") long newsId, @Param("root") long root);

    List<Long> selectCommentRequestIds(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    int insertComment(
            @Param("newsId") long newsId,
            @Param("viewer") long viewer,
            @Param("content") String content,
            @Param("clientRequestId") String clientRequestId,
            @Param("target") Long target,
            @Param("root") Long root);

    List<CommunityComment> lockCommentRequest(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    List<CommunityComment> selectSavedComment(@Param("id") long id, @Param("newsId") long newsId);

    List<Long> selectCommentAuthors(@Param("id") long id, @Param("newsId") long newsId);

    Long countOwnComment(
            @Param("id") long id, @Param("newsId") long newsId, @Param("viewer") long viewer);

    int softDeleteOwnComment(
            @Param("id") long id, @Param("newsId") long newsId, @Param("viewer") long viewer);
}
