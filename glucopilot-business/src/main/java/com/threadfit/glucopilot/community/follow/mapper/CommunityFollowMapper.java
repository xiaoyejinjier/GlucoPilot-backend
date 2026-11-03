package com.threadfit.glucopilot.community.follow.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityFollowMapper {

    @Select(
            "SELECT COUNT(*) FROM community_follow WHERE follower_id=#{viewer} AND following=1 AND"
                    + " (target_key=#{key} OR target_key IN (SELECT CONCAT('b',id) FROM"
                    + " community_blogger WHERE user_id=#{userId} AND enabled=1)) ")
    Long countUserFollowing(
            @Param("viewer") long viewer, @Param("key") String key, @Param("userId") long userId);

    @Select(
            "SELECT COUNT(*) FROM community_follow WHERE follower_id=#{viewer} AND"
                    + " target_key=#{key} AND following=1 ")
    Long countBloggerFollowing(@Param("viewer") long viewer, @Param("key") String key);

    @Select(
            "SELECT id FROM user_account WHERE id=#{viewer} AND is_deleted=0 AND account_status=1"
                    + " FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockActiveUser(@Param("viewer") long viewer);

    @Insert(
            "INSERT INTO community_follow(follower_id,target_key,following)"
                    + " VALUES(#{viewer},#{targetKey},#{following}) ON DUPLICATE KEY UPDATE"
                    + " following=#{updatedFollowing},updated_at=CURRENT_TIMESTAMP ")
    int upsertFollowing(
            @Param("viewer") long viewer,
            @Param("targetKey") String targetKey,
            @Param("following") int following,
            @Param("updatedFollowing") int updatedFollowing);

    @Update(
            "UPDATE community_follow SET following=0,updated_at=CURRENT_TIMESTAMP WHERE"
                    + " follower_id=#{viewer} AND target_key IN (SELECT CONCAT('b',id) FROM"
                    + " community_blogger WHERE user_id=#{targetUser}) AND following=1 ")
    int clearBloggerAliases(@Param("viewer") long viewer, @Param("targetUser") long targetUser);

    @Delete(
            "DELETE FROM community_notification WHERE recipient_id=#{recipient} AND"
                    + " actor_id=#{actor} AND kind='FOLLOW' ")
    int deleteFollowNotification(@Param("recipient") long recipient, @Param("actor") long actor);

    @Select(
            "SELECT COUNT(*) FROM community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.following=1 AND ((LEFT(f.target_key,1)='u' AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE CONCAT('u',u.id)=f.target_key AND u.is_deleted=0 AND"
                + " u.account_status=1)) OR (LEFT(f.target_key,1)='b' AND EXISTS(SELECT 1 FROM"
                + " community_blogger b WHERE CONCAT('b',b.id)=f.target_key AND b.enabled=1))) AND"
                + " (EXISTS(SELECT 1 FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id"
                + " AND p.is_deleted=0 WHERE f.target_key=CONCAT('u',u.id) AND"
                + " LOCATE(#{keyword},COALESCE(NULLIF(p.nickname,''),'社区用户'))>0) OR EXISTS(SELECT 1"
                + " FROM community_blogger b LEFT JOIN user_profile bp ON bp.user_id=b.user_id AND"
                + " bp.is_deleted=0 WHERE f.target_key=CONCAT('b',b.id) AND LOCATE(#{keyword},CASE"
                + " WHEN b.user_id IS NULL THEN b.nickname ELSE"
                + " COALESCE(NULLIF(bp.nickname,''),'社区用户') END)>0)) ")
    Long countFollowing(@Param("viewer") long viewer, @Param("keyword") String keyword);

    @Select(
            "SELECT f.target_key FROM community_follow f WHERE f.follower_id=#{viewer} AND"
                + " f.following=1 AND ((LEFT(f.target_key,1)='u' AND EXISTS(SELECT 1 FROM"
                + " user_account u WHERE CONCAT('u',u.id)=f.target_key AND u.is_deleted=0 AND"
                + " u.account_status=1)) OR (LEFT(f.target_key,1)='b' AND EXISTS(SELECT 1 FROM"
                + " community_blogger b WHERE CONCAT('b',b.id)=f.target_key AND b.enabled=1))) AND"
                + " (EXISTS(SELECT 1 FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id"
                + " AND p.is_deleted=0 WHERE f.target_key=CONCAT('u',u.id) AND"
                + " LOCATE(#{keyword},COALESCE(NULLIF(p.nickname,''),'社区用户'))>0) OR EXISTS(SELECT 1"
                + " FROM community_blogger b LEFT JOIN user_profile bp ON bp.user_id=b.user_id AND"
                + " bp.is_deleted=0 WHERE f.target_key=CONCAT('b',b.id) AND LOCATE(#{keyword},CASE"
                + " WHEN b.user_id IS NULL THEN b.nickname ELSE"
                + " COALESCE(NULLIF(bp.nickname,''),'社区用户') END)>0)) ORDER BY f.updated_at"
                + " DESC,f.target_key LIMIT #{limit} OFFSET #{offset} ")
    List<String> selectFollowingKeys(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
