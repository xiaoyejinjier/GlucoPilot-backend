package com.threadfit.glucopilot.ai.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 供会话记忆装配上下文：取会话内最近 N 条「可回放的用户/助手消息」（倒序返回）。
     * 只取 1/2 两类角色——系统提示词由 ChatClient 的 defaultSystem 提供，不落库也不回放。
     * <p>
     * ⚠️ 过滤条件<b>不能只认 {@code content_text}</b>：图片消息（msg_type=2）的 content_text 为空，
     * 它的可回放文本在 {@code media_caption} 里。若这里只放行 content_text，
     * 图片消息会在 <b>SQL 层</b>就被滤掉，Java 侧再怎么兜底都拿不到——
     * 表现是「摘要明明写进库里了，模型却依然不记得图片」，而且极难定位。
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND is_deleted = 0 "
            + "AND msg_role IN (1, 2) "
            + "AND ((content_text IS NOT NULL AND content_text <> '') "
            + "OR (media_caption IS NOT NULL AND media_caption <> '')) "
            + "ORDER BY seq_no DESC, id DESC LIMIT #{limit}")
    List<ChatMessage> selectRecentBySession(@Param("sessionId") Long sessionId, @Param("limit") Integer limit);

    /**
     * 回填图片内容摘要（多模态阶段 1 的产物）。
     * <p>
     * 这是 chat_message 上<b>唯一</b>的 UPDATE。该表设计为「只追加」，此处是受控例外：
     * <ul>
     *   <li>{@code AND media_caption IS NULL} 让「只填一次、永不覆盖」由 SQL 保证——
     *       同一轮即使被重试（或并发重放）也不会改写已写入的摘要；</li>
     *   <li>只碰派生字段，用户原话与已落库的助手回复都不受影响。</li>
     * </ul>
     * 返回受影响行数：0 表示该行已有摘要（或不存在/已删除），调用方无需特殊处理。
     */
    @Update("UPDATE chat_message SET media_caption = #{caption} "
            + "WHERE id = #{id} AND media_caption IS NULL AND is_deleted = 0")
    int updateMediaCaption(@Param("id") Long id, @Param("caption") String caption);

    /**
     * 逻辑删除会话内全部消息（接口 5.4 删除会话）。
     * DDL 的 chk_cm_del_consistent 要求 is_deleted=1 时 deleted_at 非空，deleted_by 可空。
     */
    @Update("UPDATE chat_message SET is_deleted = 1, deleted_at = NOW(3) "
            + "WHERE session_id = #{sessionId} AND is_deleted = 0")
    int logicDeleteBySession(@Param("sessionId") Long sessionId);
}
