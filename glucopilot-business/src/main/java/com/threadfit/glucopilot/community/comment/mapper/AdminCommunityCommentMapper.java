package com.threadfit.glucopilot.community.comment.mapper;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface AdminCommunityCommentMapper {

    @Select(
            "<script>SELECT COUNT(*) FROM community_comment c JOIN cms_news n ON n.id=c.news_id"
                + " WHERE c.is_deleted=0 AND n.is_deleted=0 <if test='status != null and status !="
                + " \"\"'> AND c.ai_status=#{status}</if> <if test='verdict != null and verdict !="
                + " \"\"'> AND c.ai_verdict=#{verdict}</if> <if test='hidden != null'> AND"
                + " c.is_hidden=#{hidden}</if> <if test='keyword != null and keyword != \"\"'> AND"
                + " (LOCATE(#{keyword},c.content)>0 OR EXISTS(SELECT 1 FROM user_profile p WHERE"
                + " p.user_id=c.user_id AND p.is_deleted=0 AND"
                + " LOCATE(#{keyword},p.nickname)>0))</if></script> ")
    Long countAdminComments(
            @Param("status") String status,
            @Param("verdict") String verdict,
            @Param("hidden") Integer hidden,
            @Param("keyword") String keyword);

    @Select(
            "<script>SELECT"
                + " c.id,c.news_id,c.content,c.user_id,c.ai_status,c.ai_verdict,c.ai_category,c.ai_severity,c.ai_confidence,c.ai_reason,c.ai_model,c.ai_prompt_ver,c.is_hidden,c.created_at,n.title,(SELECT"
                + " p.nickname FROM user_profile p WHERE p.user_id=c.user_id AND p.is_deleted=0"
                + " LIMIT 1) nickname FROM community_comment c JOIN cms_news n ON n.id=c.news_id"
                + " WHERE c.is_deleted=0 AND n.is_deleted=0 <if test='status != null and status !="
                + " \"\"'> AND c.ai_status=#{status}</if> <if test='verdict != null and verdict !="
                + " \"\"'> AND c.ai_verdict=#{verdict}</if> <if test='hidden != null'> AND"
                + " c.is_hidden=#{hidden}</if> <if test='keyword != null and keyword != \"\"'> AND"
                + " (LOCATE(#{keyword},c.content)>0 OR EXISTS(SELECT 1 FROM user_profile p WHERE"
                + " p.user_id=c.user_id AND p.is_deleted=0 AND"
                + " LOCATE(#{keyword},p.nickname)>0))</if> ORDER BY c.id DESC LIMIT #{limit} OFFSET"
                + " #{offset}</script> ")
    List<Map<String, Object>> selectAdminComments(
            @Param("status") String status,
            @Param("verdict") String verdict,
            @Param("hidden") Integer hidden,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
