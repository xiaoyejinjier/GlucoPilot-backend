package com.threadfit.glucopilot.community.shared.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 账号注销的社区私有数据清理；公开内容、点赞收藏和私信历史保留。 */
@Mapper
public interface CommunityAccountCleanupMapper {
    @Delete("DELETE FROM community_follow WHERE follower_id=#{userId}")
    int deleteFollows(@Param("userId") long userId);

    @Delete("DELETE FROM community_article_reader WHERE user_id=#{userId}")
    int deleteReaders(@Param("userId") long userId);

    @Delete("DELETE FROM community_article_view WHERE user_id=#{userId}")
    int deleteViews(@Param("userId") long userId);

    @Delete("DELETE FROM community_article_hidden WHERE user_id=#{userId}")
    int deleteHiddenArticles(@Param("userId") long userId);

    @Delete("DELETE FROM community_article_draft WHERE user_id=#{userId}")
    int deleteDrafts(@Param("userId") long userId);

    @Delete("DELETE FROM community_notification WHERE recipient_id=#{userId}")
    int deleteNotifications(@Param("userId") long userId);

    @Delete("DELETE FROM community_report WHERE reporter_id=#{userId}")
    int deleteReports(@Param("userId") long userId);

    @Update(
            "UPDATE community_blogger SET user_id=NULL,nickname='已注销用户',avatar_url=NULL,bio=''"
                    + " WHERE user_id=#{userId}")
    int anonymizeBlogger(@Param("userId") long userId);
}
