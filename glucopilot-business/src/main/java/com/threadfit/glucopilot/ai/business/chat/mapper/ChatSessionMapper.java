package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 「每用户至多一条进行中会话」的唯一键（V11 新增）。
     * <p>
     * 索引建在生成列 {@code active_user_id} 上：进行中的行等于 user_id，其余为 NULL，
     * 利用「唯一索引允许多个 NULL」表达条件唯一。并发建会话落败的一方捕获该键的 1062 后回查。
     */
    String UK_USER_ACTIVE = "uk_user_active";

    /** 取用户当前「进行中」会话；无则返回 null，由 Service 新建。 */
    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} AND session_status = 1 AND is_deleted = 0 "
            + "ORDER BY COALESCE(last_msg_at, gmt_create) DESC, id DESC LIMIT 1")
    ChatSession selectActiveByUserId(@Param("userId") Long userId);

    /**
     * 按主键取会话，<b>包含已逻辑删除的行</b>（绕开 {@code @TableLogic} 的自动过滤）。
     * <p>
     * 只给"删除接口的幂等判断"用：需要区分三种情况——
     * 从来没有过 / 是别人的 / 是自己的但已经删了。用普通的 {@code selectById} 前两种都会返回 null，
     * 于是"重复删除"要么被误报成 404（前端重试就弹错），要么为了幂等把"别人的会话"也回成功
     * ——后者更糟：它把 404 与 200 的差异变成一条探测"该 ID 是否存在"的信道。
     */
    @Select("SELECT * FROM chat_session WHERE id = #{id}")
    ChatSession selectByIdIncludingDeleted(@Param("id") Long id);

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

    // ------------------------------------------------------------------ 生命周期（5.3 / 5.4 / 新建 / 置顶）

    /**
     * 结束用户当前「进行中」会话（置 2 已结束）。
     * <p>
     * 「新建会话」必须在**同一事务**里先调它、再插新会话：{@code uk_user_active} 建在生成列
     * {@code active_user_id} 上（仅 {@code session_status = 1 AND is_deleted = 0} 时等于 user_id），
     * 不先结束旧会话就插新会话必然撞 1062。只影响该用户，且只动 session_status=1 的行。
     */
    @Update("UPDATE chat_session SET session_status = 2 "
            + "WHERE user_id = #{userId} AND session_status = 1 AND is_deleted = 0")
    int endActiveSessions(@Param("userId") Long userId);

    /**
     * 惰性归档：把「进行中但太久没说话」的会话置为 0 归档。
     * <p>
     * 选择惰性（在拉列表时顺手做）而不是定时任务：归档只是列表展示语义，不值得为它引入
     * quartz 任务的部署与多实例去重成本；而且它同时解决了"用户 3 个月后再进来，仍被当成
     * 接着上次那条会话说话"的问题——下一次 /current 会新建会话，而不是复用老会话。
     * <p>
     * 只归档 {@code last_msg_at < before} 的行；{@code last_msg_at IS NULL}（建了但没说过话）
     * 按 {@code gmt_create} 判，否则那些空会话永远不会被归档。
     */
    @Update("UPDATE chat_session SET session_status = 0 "
            + "WHERE user_id = #{userId} AND session_status = 1 AND is_deleted = 0 "
            + "AND COALESCE(last_msg_at, gmt_create) < #{before}")
    int archiveIdleSessions(@Param("userId") Long userId, @Param("before") LocalDateTime before);

    /**
     * 逻辑删除会话（5.4）。带 user_id 条件：越权删除不能靠"先查再删"两步，
     * 一条语句里把归属写死更安全（并发下也不会删到别人的会话）。
     */
    @Update("UPDATE chat_session SET is_deleted = 1, deleted_at = NOW(3), deleted_by = #{userId} "
            + "WHERE id = #{sessionId} AND user_id = #{userId} AND is_deleted = 0")
    int logicDeleteSession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /** 置顶/取消置顶；同样把 user_id 写进条件。 */
    @Update("UPDATE chat_session SET is_pinned = #{pinned} "
            + "WHERE id = #{sessionId} AND user_id = #{userId} AND is_deleted = 0")
    int updatePinned(@Param("sessionId") Long sessionId, @Param("userId") Long userId,
                     @Param("pinned") int pinned);

    // ------------------------------------------------------------------ 滚动摘要（② 期）

    /**
     * 只取滚动摘要正文（不取整行）。
     * <p>
     * {@code context_summary} 是 MEDIUMTEXT，只在装配上下文时读一次；单独取列避免把整行
     * （含 title 等无关字段、以及将来可能的其它大列）都拉回来。
     */
    @Select("SELECT context_summary FROM chat_session WHERE id = #{sessionId} AND is_deleted = 0")
    String selectContextSummary(@Param("sessionId") Long sessionId);

    /** 摘要已覆盖到哪条消息（null = 还没摘要过）：摘要任务据此只压缩"还没被覆盖"的原文。 */
    @Select("SELECT summary_upto_msg_id FROM chat_session WHERE id = #{sessionId} AND is_deleted = 0")
    Long selectSummaryUptoMsgId(@Param("sessionId") Long sessionId);

    /**
     * 写入滚动摘要并推进"摘要已覆盖到哪条消息"。
     * <p>
     * <b>只允许前进，不允许回退</b>（{@code summary_upto_msg_id < #{uptoMsgId}}）：摘要任务是异步的，
     * 慢任务可能后完成；没有这个条件的话，一次晚到的写入会把已经覆盖更多的指针拉回去，
     * 下一轮就会把已经压缩过的内容再压一遍（重复且越压越糊）。
     */
    @Update("UPDATE chat_session SET context_summary = #{summary}, summary_upto_msg_id = #{uptoMsgId} "
            + "WHERE id = #{sessionId} AND is_deleted = 0 "
            + "AND (summary_upto_msg_id IS NULL OR summary_upto_msg_id < #{uptoMsgId})")
    int updateSummary(@Param("sessionId") Long sessionId, @Param("summary") String summary,
                      @Param("uptoMsgId") Long uptoMsgId);

    /** 账号注销：物理删除该用户全部会话（历史删除态一并清掉）。 */
    @Delete("DELETE FROM chat_session WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
