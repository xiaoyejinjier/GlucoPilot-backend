package com.threadfit.glucopilot.community.follow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.*;

/** SQL位于resources/com/threadfit/glucopilot/community/follow/mapper/CommunityFollowMapper.xml。 */
@Mapper
public interface CommunityFollowMapper {

    Long countUserFollowing(
            @Param("viewer") long viewer, @Param("key") String key, @Param("userId") long userId);

    Long countBloggerFollowing(@Param("viewer") long viewer, @Param("key") String key);

    List<Long> lockActiveUser(@Param("viewer") long viewer);

    int upsertFollowing(
            @Param("viewer") long viewer,
            @Param("targetKey") String targetKey,
            @Param("following") int following,
            @Param("updatedFollowing") int updatedFollowing);

    int clearBloggerAliases(@Param("viewer") long viewer, @Param("targetUser") long targetUser);

    int deleteFollowNotification(@Param("recipient") long recipient, @Param("actor") long actor);

    Long countFollowing(@Param("viewer") long viewer, @Param("keyword") String keyword);

    List<String> selectFollowingKeys(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
