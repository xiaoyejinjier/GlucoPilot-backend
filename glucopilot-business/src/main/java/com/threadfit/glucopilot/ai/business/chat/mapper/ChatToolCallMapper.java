package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工具调用元数据读写（V21）。只追加：没有 UPDATE / DELETE。
 */
public interface ChatToolCallMapper extends BaseMapper<ChatToolCall> {

    /** 按用户消息查本轮的工具调用（时序正序），用于排障与"这一轮到底查了什么"。 */
    @Select("SELECT * FROM chat_tool_call WHERE user_msg_id = #{userMsgId} ORDER BY call_index ASC, id ASC")
    List<ChatToolCall> selectByUserMsgId(@Param("userMsgId") Long userMsgId);

    /** 按会话查（最近 N 条，倒序），用于后台/排障查看某次对话的工具使用情况。 */
    @Select("SELECT * FROM chat_tool_call WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<ChatToolCall> selectRecentBySession(@Param("sessionId") Long sessionId, @Param("limit") Integer limit);

    /**
     * 取最近 N 条**带结果摘要**的调用（时间正序返回），用于会话记忆回放（B3 后半）。
     * <p>
     * 只取有 digest 的行：早于 ② 期的历史数据没有摘要，返回空串只会白占预算。
     */
    @Select("SELECT tool_name, result_digest, gmt_create FROM chat_tool_call "
            + "WHERE session_id = #{sessionId} AND result_digest IS NOT NULL AND result_digest <> '' "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<ChatToolCall> selectRecentDigests(@Param("sessionId") Long sessionId, @Param("limit") Integer limit);

    /**
     * 审计分页：可按用户 / 会话 / 工具名 / 成败过滤，按主键倒序（新写入的排前面）。
     *
     * <p>为什么把这条 SQL 放在本 mapper 而不是 {@code audit} 包：表由 chat 域拥有，
     * 按「谁的表谁写 SQL」的原则落在这里，审计服务只负责调用与摊平。
     */
    @Select("<script>"
            + "SELECT * FROM chat_tool_call WHERE 1 = 1"
            + "<if test='userId != null'> AND user_id = #{userId}</if>"
            + "<if test='sessionId != null'> AND session_id = #{sessionId}</if>"
            + "<if test='toolName != null'> AND tool_name = #{toolName}</if>"
            + "<if test='ok != null'> AND ok = #{ok}</if>"
            + " ORDER BY id DESC"
            + "</script>")
    IPage<ChatToolCall> selectAuditPage(IPage<ChatToolCall> page,
                                       @Param("userId") Long userId,
                                       @Param("sessionId") Long sessionId,
                                       @Param("toolName") String toolName,
                                       @Param("ok") Integer ok);

    /**
     * 账号注销：物理删除工具调用记录。
     * <p>
     * result_digest 是工具的返回摘要，里面可能有血糖区间等健康事实，
     * 所以它必须与对话正文一起删，不能因为"只是元数据"就留下。
     */
    @Delete("DELETE FROM chat_tool_call WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);
}
