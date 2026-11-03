package com.threadfit.glucopilot.community.notification.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityEventMapper {

    @Select(
            "SELECT b.user_id FROM cms_news n JOIN community_blogger b ON b.id=n.blogger_id JOIN"
                    + " user_account u ON u.id=b.user_id AND u.is_deleted=0 AND u.account_status=1"
                    + " WHERE n.id=#{newsId} AND n.is_deleted=0 ")
    List<Long> selectArticleAuthorUsers(@Param("newsId") long newsId);

    @Select(
            "SELECT COUNT(*) FROM user_account WHERE id IN (#{recipient},#{actor}) AND is_deleted=0"
                    + " AND account_status=1 ")
    Long countActiveParticipants(@Param("recipient") long recipient, @Param("actor") long actor);

    @Insert(
            "INSERT INTO"
                + " community_notification(recipient_id,actor_id,kind,target_id,news_id,comment_id)"
                + " VALUES(#{recipient},#{actor},#{kind},#{target},#{newsId},#{commentId}) ON"
                + " DUPLICATE KEY UPDATE id=id ")
    int insertNotification(
            @Param("recipient") long recipient,
            @Param("actor") long actor,
            @Param("kind") String kind,
            @Param("target") long target,
            @Param("newsId") Long newsId,
            @Param("commentId") Long commentId);

    @Delete(
            "DELETE FROM community_notification WHERE actor_id=#{actor} AND kind=#{kind} AND"
                    + " target_id=#{newsId} ")
    int deleteActionNotification(
            @Param("actor") long actor, @Param("kind") String kind, @Param("newsId") long newsId);
}
