package com.threadfit.glucopilot.community.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.message.entity.CommunityConversationRow;
import com.threadfit.glucopilot.community.message.entity.CommunityMessage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.*;

/** SQL位于resources/com/threadfit/glucopilot/community/message/mapper/CommunityMessageMapper.xml。 */
@Mapper
public interface CommunityMessageMapper extends BaseMapper<CommunityMessage> {

    // 对方是否发过消息给我：用于判断是否已获得回复、可继续发送。
    Long countReceivedMessages(@Param("peer") long peer, @Param("viewer") long viewer);

    // 我是否已向对方发过消息：未互关、未获回复时，只允许首条。
    Long countSentMessages(@Param("viewer") long viewer, @Param("peer") long peer);

    // 【并发锁】锁有效的双方账号记录，直到Service事务结束；查询ID不代表修改账号资料。
    // XML中的useCache=false、flushCache=true确保锁查询实际访问数据库。
    List<Long> lockMessageParticipants(
            @Param("firstUser") long firstUser, @Param("secondUser") long secondUser);

    // 【幂等查询】以发送者+请求编号读取已保存消息；空列表表示尚未处理。
    List<CommunityMessage> selectMessageRequest(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    // 【消息落库】V29中的uk_cm_request唯一索引防止同一发送者重复使用同一编号保存消息。
    int insertMessage(
            @Param("viewer") long viewer,
            @Param("peer") long peer,
            @Param("content") String content,
            @Param("clientRequestId") String clientRequestId);

    // 插入成功或触发唯一键冲突后，按同一编号读回最终消息，供Service核对并返回。
    CommunityMessage selectSavedMessage(
            @Param("viewer") long viewer, @Param("clientRequestId") String clientRequestId);

    // 【游标分页】仅查双方消息；id小于beforeId避免重复，倒序取最近的若干条。
    List<CommunityMessage> selectMessagesBefore(
            @Param("viewer") long viewer,
            @Param("peer") long peer,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    Long countConversationMessage(
            @Param("id") long id, @Param("viewer") long viewer, @Param("peer") long peer);

    // 仅把对方发给我的、截至through且尚未读的消息标为已读。
    int markMessagesRead(
            @Param("peer") long peer, @Param("viewer") long viewer, @Param("through") long through);

    Long countConversations(@Param("viewer") long viewer);

    List<CommunityConversationRow> selectConversations(
            @Param("viewer") long viewer, @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> selectUnread(@Param("viewer") long viewer);
}
