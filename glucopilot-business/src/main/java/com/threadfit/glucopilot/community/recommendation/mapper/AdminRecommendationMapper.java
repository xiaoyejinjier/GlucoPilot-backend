package com.threadfit.glucopilot.community.recommendation.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AdminRecommendationMapper {
    @Select(
            "SELECT u.id,COALESCE(NULLIF(p.nickname,''),CONCAT('用户 ',u.id)) nickname FROM"
                + " user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0"
                + " WHERE u.id=#{id} AND u.is_deleted=0 AND u.account_status=1")
    Map<String, Object> user(@Param("id") long id);

    @Select("SELECT id,code,name FROM community_tag WHERE enabled=1 ORDER BY id")
    List<Map<String, Object>> tags();

    @Select(
            "<script>SELECT atg.news_id,t.id,t.name FROM community_article_tag atg JOIN"
                    + " community_tag t ON t.id=atg.tag_id AND t.enabled=1 WHERE atg.news_id IN"
                    + " <foreach collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach> ORDER BY atg.news_id,t.id</script>")
    List<Map<String, Object>> articleTags(@Param("ids") List<Long> ids);

    @Select(
            "<script>SELECT n.id,COALESCE(NULLIF(p.nickname,''),NULLIF(b.nickname,''),'社区用户')"
                + " author FROM cms_news n LEFT JOIN community_article a ON a.news_id=n.id LEFT"
                + " JOIN user_profile p ON p.user_id=a.author_user_id AND p.is_deleted=0 LEFT JOIN"
                + " community_blogger b ON b.id=n.blogger_id WHERE n.id IN <foreach"
                + " collection='ids' item='id' open='(' separator=','"
                + " close=')'>#{id}</foreach></script>")
    List<Map<String, Object>> authors(@Param("ids") List<Long> ids);
}
