package com.threadfit.glucopilot.community.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.*;

/**
 * SQL位于resources/com/threadfit/glucopilot/community/comment/mapper/AdminCommunityCommentMapper.xml。
 */
@Mapper
public interface AdminCommunityCommentMapper {

    Long countAdminComments(
            @Param("status") String status,
            @Param("verdict") String verdict,
            @Param("hidden") Integer hidden,
            @Param("keyword") String keyword);

    List<Map<String, Object>> selectAdminComments(
            @Param("status") String status,
            @Param("verdict") String verdict,
            @Param("hidden") Integer hidden,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
