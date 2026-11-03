package com.threadfit.glucopilot.community.profile.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityProfileMapper {

    @Select(
            "SELECT COUNT(*) FROM user_account u WHERE u.id=#{userId} AND u.is_deleted=0 AND"
                    + " u.account_status=1 ")
    Long countActiveUser(@Param("userId") long userId);

    @Select(
            "SELECT u.id,COALESCE(NULLIF(p.nickname,''),'社区用户') nickname,COALESCE(p.avatar_url,'')"
                + " avatar_url,COALESCE(p.bio,'') bio FROM user_account u LEFT JOIN user_profile p"
                + " ON p.user_id=u.id AND p.is_deleted=0  WHERE u.id=#{id} AND u.is_deleted=0 AND"
                + " u.account_status=1 ")
    List<Map<String, Object>> selectUserProfile(@Param("id") long id);

    @Select(
            "SELECT b.*,u.id linked_id FROM community_blogger b LEFT JOIN user_account u ON"
                + " u.id=b.user_id AND u.is_deleted=0 AND u.account_status=1 WHERE b.id=#{id} AND"
                + " b.enabled=1 ")
    List<Map<String, Object>> selectBloggerProfile(@Param("id") long id);

    @Select(
            "SELECT b.user_id FROM cms_news n JOIN community_blogger b ON b.id=n.blogger_id JOIN"
                    + " user_account u ON u.id=b.user_id AND u.is_deleted=0 AND u.account_status=1"
                    + " WHERE n.id=#{newsId} AND n.is_deleted=0 ")
    List<Long> selectArticleUsers(@Param("newsId") long newsId);

    @Select("SELECT blogger_id FROM cms_news WHERE id=#{newsId} AND is_deleted=0 ")
    Long selectArticleBlogger(@Param("newsId") long newsId);

    @Select(
            "SELECT COUNT(*) FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND"
                + " p.is_deleted=0  WHERE u.is_deleted=0 AND u.account_status=1 AND u.id<>#{viewer}"
                + " AND LOCATE(#{keyword},COALESCE(p.nickname,''))>0 ")
    Long countProfileSearch(@Param("viewer") long viewer, @Param("keyword") String keyword);

    @Select(
            "SELECT u.id FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND"
                + " p.is_deleted=0  WHERE u.is_deleted=0 AND u.account_status=1 AND u.id<>#{viewer}"
                + " AND LOCATE(#{keyword},COALESCE(p.nickname,''))>0 ORDER BY u.id DESC LIMIT"
                + " #{limit} OFFSET #{offset} ")
    List<Long> selectProfileSearchIds(
            @Param("viewer") long viewer,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
