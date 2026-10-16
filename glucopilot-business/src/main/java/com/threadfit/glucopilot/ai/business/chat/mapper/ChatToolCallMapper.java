package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
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
}
