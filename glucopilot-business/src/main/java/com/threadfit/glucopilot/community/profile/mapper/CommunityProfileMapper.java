package com.threadfit.glucopilot.community.profile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.*;

/** SQL位于resources/com/threadfit/glucopilot/community/profile/mapper/CommunityProfileMapper.xml。 */
@Mapper
public interface CommunityProfileMapper {

    Long countActiveUser(@Param("userId") long userId);

    List<Map<String, Object>> selectUserProfile(@Param("id") long id);

    List<Map<String, Object>> selectBloggerProfile(@Param("id") long id);

    List<Long> selectArticleUsers(@Param("newsId") long newsId);

    Long selectArticleBlogger(@Param("newsId") long newsId);

    Long countProfileSearch(@Param("viewer") long viewer, @Param("keyword") String keyword);

    List<Long> selectProfileSearchIds(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
