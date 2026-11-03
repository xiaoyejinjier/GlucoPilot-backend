package com.threadfit.glucopilot.community.media.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityArticlePresentationMapper {

    @Select(
            "SELECT i.media_id FROM community_article_image i JOIN media_file m ON m.id=i.media_id"
                    + " WHERE i.news_id=#{newsId} AND m.biz_type=7 ORDER BY i.sort_no ")
    List<Map<String, Object>> selectArticleImages(@Param("newsId") long newsId);

    @Select(
            "SELECT i.media_id FROM community_article_image i JOIN media_file m ON m.id=i.media_id"
                    + " WHERE i.news_id=#{newsId} AND m.biz_type=8 ORDER BY i.sort_no ")
    List<Map<String, Object>> selectArticleVideos(@Param("newsId") long newsId);

    @Select(
            "SELECT t.code,t.name FROM community_article_tag a JOIN community_tag t ON"
                + " t.id=a.tag_id WHERE a.news_id=#{newsId} AND t.enabled=1 ORDER BY t.sort_no,t.id"
                + " ")
    List<Map<String, Object>> selectArticleTags(@Param("newsId") long newsId);
}
