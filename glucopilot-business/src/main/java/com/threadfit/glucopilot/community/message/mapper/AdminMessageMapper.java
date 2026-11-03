package com.threadfit.glucopilot.community.message.mapper;

import com.threadfit.glucopilot.community.message.entity.AdminMessageRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdminMessageMapper {
    String FROM =
            """
FROM community_message m
LEFT JOIN user_profile sender ON sender.user_id=m.sender_id AND sender.is_deleted=0
LEFT JOIN user_profile recipient ON recipient.user_id=m.recipient_id AND recipient.is_deleted=0
WHERE 1=1
<if test="q.senderId != null">AND m.sender_id=#{q.senderId}</if>
<if test="q.recipientId != null">AND m.recipient_id=#{q.recipientId}</if>
<if test="q.readState == 'READ'">AND m.read_at IS NOT NULL</if>
<if test="q.readState == 'UNREAD'">AND m.read_at IS NULL</if>
<if test="q.keyword != null">
  AND (LOCATE(#{q.keyword},m.content)>0 OR LOCATE(#{q.keyword},sender.nickname)>0
       OR LOCATE(#{q.keyword},recipient.nickname)>0)
</if>
<if test="q.firstUserId != null">
  AND ((m.sender_id=#{q.firstUserId} AND m.recipient_id=#{q.secondUserId})
    OR (m.sender_id=#{q.secondUserId} AND m.recipient_id=#{q.firstUserId}))
</if>
""";

    @Select("<script>SELECT COUNT(*) " + FROM + "</script>")
    long count(@Param("q") AdminMessageQuery query);

    @Select(
            """
<script>SELECT m.id,m.sender_id,m.recipient_id,m.content,m.created_at,m.read_at,
  COALESCE(NULLIF(sender.nickname,''),CONCAT('用户 ',m.sender_id)) sender_nickname,
  COALESCE(NULLIF(recipient.nickname,''),CONCAT('用户 ',m.recipient_id)) recipient_nickname
"""
                    + FROM
                    + " ORDER BY m.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AdminMessageRow> page(
            @Param("q") AdminMessageQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
