package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 「每用户至多一条进行中会话」的唯一键（V10 新增）。
     * <p>
     * 索引建在生成列 {@code active_user_id} 上：进行中的行等于 user_id，其余为 NULL，
     * 利用「唯一索引允许多个 NULL」表达条件唯一。并发建会话落败的一方捕获该键的 1062 后回查。
     */
    String UK_USER_ACTIVE = "uk_user_active";

    /** 取用户当前「进行中」会话；无则返回 null，由 Service 新建。 */
    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} AND session_status = 1 AND is_deleted = 0 "
            + "ORDER BY COALESCE(last_msg_at, gmt_create) DESC, id DESC LIMIT 1")
    ChatSession selectActiveByUserId(@Param("userId") Long userId);

    /** 消息计数与最近消息时间：用 SQL 自增，避免读改写竞态。gmt_modified 由 DDL 的 ON UPDATE 刷新。 */
    @Update("UPDATE chat_session SET msg_count = msg_count + 1, last_msg_at = #{lastMsgAt} "
            + "WHERE id = #{sessionId} AND is_deleted = 0")
    int touchOnMessage(@Param("sessionId") Long sessionId, @Param("lastMsgAt") LocalDateTime lastMsgAt);

    /** 首条用户消息到达时，把默认标题替换为消息摘要。 */
    @Update("UPDATE chat_session SET title = #{title} WHERE id = #{sessionId} AND is_deleted = 0 "
            + "AND (title IS NULL OR title = '' OR title = #{defaultTitle})")
    int renameIfDefault(@Param("sessionId") Long sessionId,
                        @Param("title") String title,
                        @Param("defaultTitle") String defaultTitle);
}
