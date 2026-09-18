package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 会话内下一个顺序号。 */
    @Select("SELECT COALESCE(MAX(seq_no), 0) + 1 FROM chat_message WHERE session_id = #{sessionId} AND is_deleted = 0")
    Integer selectNextSeqNo(@Param("sessionId") Long sessionId);

    /** 幂等回查：同会话同 clientMsgId 的用户消息。 */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND client_msg_id = #{clientMsgId} "
            + "AND is_deleted = 0 ORDER BY id DESC LIMIT 1")
    ChatMessage selectByClientMsgId(@Param("sessionId") Long sessionId, @Param("clientMsgId") String clientMsgId);

    /** 最近一条用户消息，用于未传 replyToMsgId 时兜底。 */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND msg_role = 1 AND is_deleted = 0 "
            + "ORDER BY seq_no DESC, id DESC LIMIT 1")
    ChatMessage selectLatestUserMessage(@Param("sessionId") Long sessionId);

    /** 幂等回查：该用户消息是否已经有助手回复（存在则重放 done，不再调用模型）。 */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND msg_role = 2 "
            + "AND reply_to_msg_id = #{replyToMsgId} AND is_deleted = 0 ORDER BY id DESC LIMIT 1")
    ChatMessage selectAssistantReply(@Param("sessionId") Long sessionId, @Param("replyToMsgId") Long replyToMsgId);

    /** 取窗口内最近 N 条（倒序返回，调用方反转成正序）。 */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND is_deleted = 0 "
            + "AND seq_no <= #{maxSeqNo} ORDER BY seq_no DESC, id DESC LIMIT #{limit}")
    List<ChatMessage> selectRecent(@Param("sessionId") Long sessionId,
                                   @Param("maxSeqNo") Integer maxSeqNo,
                                   @Param("limit") Integer limit);
}
