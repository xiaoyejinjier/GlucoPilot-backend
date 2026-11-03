package com.threadfit.glucopilot.community.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.message.entity.CommunityConversationRow;
import com.threadfit.glucopilot.community.message.entity.CommunityMessage;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface CommunityMessageMapper extends BaseMapper<CommunityMessage> {

    // 对方是否发过消息给我：用于判断是否已获得回复、可继续发送。
    @Select(
            """
            SELECT COUNT(*)
            FROM community_message
            WHERE sender_id = #{peer}
              AND recipient_id = #{viewer}
            """)
    Long countReceivedMessages(@Param("peer") long peer, @Param("viewer") long viewer);

    // 我是否已向对方发过消息：未互关、未获回复时，只允许首条。
    @Select(
            """
            SELECT COUNT(*)
            FROM community_message
            WHERE sender_id = #{viewer}
              AND recipient_id = #{peer}
            """)
    Long countSentMessages(@Param("viewer") long viewer, @Param("peer") long peer);

    // 【并发锁】锁有效的双方账号记录，直到Service事务结束；查询ID不代表修改账号资料。
    // Options禁用二级缓存并清理会话缓存，确保锁查询实际访问数据库。
    @Select(
            """
            SELECT id
            FROM user_account
            WHERE id IN (#{firstUser}, #{secondUser})
              AND is_deleted = 0
              AND account_status = 1
            ORDER BY id
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockMessageParticipants(
            @Param("firstUser") long firstUser, @Param("secondUser") long secondUser);

    // 【幂等查询】以发送者+请求编号读取已保存消息；空列表表示尚未处理。
    @Select(
            """
            SELECT *
            FROM community_message
            WHERE sender_id = #{viewer}
              AND client_request_id = #{clientRequestId}
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<CommunityMessage> selectMessageRequest(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    // 【消息落库】V29中的uk_cm_request唯一索引防止同一发送者重复使用同一编号保存消息。
    @Insert(
            """
            INSERT INTO community_message (
                sender_id,
                recipient_id,
                content,
                client_request_id
            )
            VALUES (
                #{viewer},
                #{peer},
                #{content},
                #{clientRequestId}
            )
            """)
    int insertMessage(
            @Param("viewer") long viewer,
            @Param("peer") long peer,
            @Param("content") String content,
            @Param("clientRequestId") String clientRequestId);

    // 插入成功或触发唯一键冲突后，按同一编号读回最终消息，供Service核对并返回。
    @Select(
            """
            SELECT *
            FROM community_message
            WHERE sender_id = #{viewer}
              AND client_request_id = #{clientRequestId}
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    CommunityMessage selectSavedMessage(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    // 【游标分页】仅查双方消息；id小于beforeId避免重复，倒序取最近的若干条。
    @Select(
            """
            <script>
            SELECT *
            FROM community_message
            WHERE (
                (sender_id = #{viewer} AND recipient_id = #{peer})
                OR (sender_id = #{peer} AND recipient_id = #{viewer})
            )
            <if test="beforeId != null">
              AND id &lt; #{beforeId}
            </if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<CommunityMessage> selectMessagesBefore(
            @Param("viewer") long viewer,
            @Param("peer") long peer,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    @Select(
            """
            SELECT COUNT(*)
            FROM community_message
            WHERE id = #{id}
              AND (
                  (sender_id = #{viewer} AND recipient_id = #{peer})
                  OR (sender_id = #{peer} AND recipient_id = #{viewer})
              )
            """)
    Long countConversationMessage(
            @Param("id") long id, @Param("viewer") long viewer, @Param("peer") long peer);

    // 仅把对方发给我的、截至through且尚未读的消息标为已读。
    @Update(
            """
            UPDATE community_message
            SET read_at = CURRENT_TIMESTAMP
            WHERE sender_id = #{peer}
              AND recipient_id = #{viewer}
              AND id <= #{through}
              AND read_at IS NULL
            """)
    int markMessagesRead(
            @Param("peer") long peer, @Param("viewer") long viewer, @Param("through") long through);

    @Select(
            """
            SELECT COUNT(*)
            FROM (
                SELECT
                    CASE
                        WHEN sender_id = #{viewer} THEN recipient_id
                        ELSE sender_id
                    END AS peer_id,
                    MAX(id) AS last_id,
                    SUM(
                        CASE
                            WHEN recipient_id = #{viewer} AND read_at IS NULL THEN 1
                            ELSE 0
                        END
                    ) AS unread_count
                FROM community_message
                WHERE sender_id = #{viewer}
                   OR recipient_id = #{viewer}
                GROUP BY peer_id
            ) t
            JOIN user_account u ON u.id = t.peer_id
              AND u.is_deleted = 0
              AND u.account_status = 1
            """)
    Long countConversations(@Param("viewer") long viewer);

    @Select(
            """
            SELECT t.peer_id, t.unread_count, m.*
            FROM (
                SELECT
                    CASE
                        WHEN sender_id = #{viewer} THEN recipient_id
                        ELSE sender_id
                    END AS peer_id,
                    MAX(id) AS last_id,
                    SUM(
                        CASE
                            WHEN recipient_id = #{viewer} AND read_at IS NULL THEN 1
                            ELSE 0
                        END
                    ) AS unread_count
                FROM community_message
                WHERE sender_id = #{viewer}
                   OR recipient_id = #{viewer}
                GROUP BY peer_id
            ) t
            JOIN user_account u ON u.id = t.peer_id
              AND u.is_deleted = 0
              AND u.account_status = 1
            JOIN community_message m ON m.id = t.last_id
            ORDER BY t.last_id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CommunityConversationRow> selectConversations(
            @Param("viewer") long viewer, @Param("limit") int limit, @Param("offset") long offset);

    @Select(
            """
            SELECT t.unread_count, m.id, m.sender_id
            FROM (
                SELECT COUNT(*) AS unread_count, MAX(m.id) AS latest_id
                FROM community_message m
                JOIN user_account u ON u.id = m.sender_id
                  AND u.is_deleted = 0
                  AND u.account_status = 1
                WHERE m.recipient_id = #{viewer}
                  AND m.read_at IS NULL
            ) t
            LEFT JOIN community_message m ON m.id = t.latest_id
            """)
    Map<String, Object> selectUnread(@Param("viewer") long viewer);
}
