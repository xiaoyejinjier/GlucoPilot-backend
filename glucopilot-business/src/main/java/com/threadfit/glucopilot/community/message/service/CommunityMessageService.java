package com.threadfit.glucopilot.community.message.service;

import static com.threadfit.glucopilot.community.message.dto.MessageDtos.*;
import static com.threadfit.glucopilot.community.shared.support.CommunityValidation.*;
import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.Conversation;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.Message;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.MessagePermission;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.MessageRequest;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.Messages;
import com.threadfit.glucopilot.community.message.dto.MessageDtos.Unread;
import com.threadfit.glucopilot.community.message.entity.CommunityMessage;
import com.threadfit.glucopilot.community.message.mapper.CommunityMessageMapper;
import com.threadfit.glucopilot.community.profile.service.CommunityProfileService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CommunityMessageService {
    private final CommunityMessageMapper mapper;
    private final CommunityProfileService profiles;

    private void requirePeer(long viewer, long peer) {
        profiles.requireUser(viewer);
        profiles.requireUser(peer);
        if (viewer == peer) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能给自己发私信");
        }
    }

    // 将数据库实体转换为接口消息，最后一项标记是否由当前用户发送。
    private Message message(long viewer, CommunityMessage row) {
        if (row == null) {
            return null;
        }
        return new Message(
                row.getId().toString(),
                row.getSenderId().toString(),
                row.getRecipientId().toString(),
                row.getContent(),
                row.getCreatedAt(),
                row.getReadAt(),
                row.getSenderId() == viewer);
    }

    // 【发送规则】互相关注或收到过对方消息即可继续发；否则仅允许先发一条。
    // 单方面关注、对方只读未回，都不会恢复首条消息额度。
    private MessagePermission messagePermission(long viewer, long peer) {
        boolean follows = profiles.following(viewer, "u" + peer),
                mutual = follows && profiles.following(peer, "u" + viewer);
        boolean received = mapper.countReceivedMessages(peer, viewer) > 0;
        if (mutual || received) {
            return new MessagePermission(true, follows, mutual, "OPEN");
        }
        boolean sent = mapper.countSentMessages(viewer, peer) > 0;
        return new MessagePermission(
                !sent, follows, false, sent ? "WAITING_REPLY" : "FIRST_MESSAGE");
    }

    // 【事务边界】加锁、查重、权限检查和保存处于同一事务；提交/回滚后才释放锁。
    @Transactional
    public Message send(long viewer, long peer, MessageRequest request) {
        // 【1. 参数校验】校验正文长度和请求编号，禁止给自己发送。
        String content = validateText(request.content(), 1000, request.clientRequestId());
        if (viewer == peer) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能给自己发私信");
        }
        // 【2. 并发控制】统一按小ID、大ID访问，降低反向争抢风险；真正加锁的是SQL的FOR UPDATE。
        // 账号行作为共同锁定对象，不修改账号资料；竞争请求等待后再检查，避免同时用掉首条额度。
        var users = mapper.lockMessageParticipants(
                Math.min(viewer, peer),
                Math.max(viewer, peer));

        // 必须查到两个未删除且正常的账号；users只保存ID，锁由数据库持有。
        if (users.size() != 2) {
            throw missing();
        }
        // 【3. 幂等检查】发送者ID + clientRequestId定位同一次发送；重试必须沿用原编号。
        var previous =
                mapper.selectMessageRequest(
                        viewer,
                        request.clientRequestId())
                        .stream()
                        .map(rs -> message(viewer, rs))
                        .collect(
                                java.util.stream.Collectors.toCollection(ArrayList::new));
        // 已保存则核对接收者和正文，直接返回原消息；不会继续执行后面的INSERT。
        if (!previous.isEmpty()) {
            return sameMessage(previous.getFirst(), peer, content);
        }

        // 【4. 业务权限】只有新请求才检查额度；已发首条且未获回复/互关时拒绝继续发送。
        if (!messagePermission(viewer, peer).canSend()) {
            throw new BizException(ErrorCode.MESSAGE_REQUEST_PENDING);
        }
        // 【5. 保存消息】写入community_message；唯一约束(sender_id, client_request_id)兜底防重。
        try {
            mapper.insertMessage(viewer, peer, content, request.clientRequestId());
        } catch (DuplicateKeyException ex) {
            // 不再重复插入；继续按原编号读取已有消息，并核对是否确为同一次发送。
        }
        // 【6. 返回结果】读回数据库中的消息ID和时间；查不到则抛异常，由事务回滚。
        Message saved = message(viewer, mapper.selectSavedMessage(viewer, request.clientRequestId()));
        if (saved == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "发送失败，请重试");
        }
        return sameMessage(saved, peer, content);
    }

    // 同一请求编号不能改接收者或正文；一致才返回旧结果，不一致返回冲突。
    private Message sameMessage(Message saved, long peer, String content) {
        if (!saved.recipientId().equals(Long.toString(peer)) || !saved.content().equals(content)) {
            throw new BizException(ErrorCode.CONFLICT, "发送标识已使用，请重新确认消息");
        }
        return saved;
    }

    // 【聊天历史】仅查询双方消息；before是游标，多查一条判断是否还有更早的消息。
    public Messages messages(long viewer, long peer, Long before, int size) {
        requirePeer(viewer, peer);
        if (size < 1 || size > 50 || (before != null && before < 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分页参数错误");
        }
        var rows = mapper.selectMessagesBefore(viewer, peer, before, size + 1).stream()
                .map(rs -> message(viewer, rs))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean more = rows.size() > size;
        if (more) {
            rows.removeLast();
        }
        // SQL倒序取最近消息，返回前翻转为从旧到新，便于聊天界面展示。
        Collections.reverse(rows);
        return new Messages(
                rows,
                more,
                rows.isEmpty() ? null : rows.getFirst().id(),
                messagePermission(viewer, peer));
    }

    // 【已读边界】先确认through属于本会话，再标记对方发来的消息；不影响之后的新消息。
    @Transactional
    public void read(long viewer, long peer, String through) {
        requirePeer(viewer, peer);
        long id;
        try {
            id = Long.parseLong(through);
            if (id < 1) {
                throw new NumberFormatException();
            }
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "消息标识不合法");
        }
        if (mapper.countConversationMessage(id, viewer, peer) != 1) {
            throw missing();
        }
        mapper.markMessagesRead(peer, viewer, id);
    }

    // 【会话列表】每个聊天对象显示最后一条消息、公开资料和未读数量。
    public PageResult<Conversation> inbox(long viewer, PageQuery q) {
        profiles.requireUser(viewer);
        page(q);
        long total = mapper.countConversations(viewer);
        var rows = mapper.selectConversations(viewer, q.getPageSize(), q.offset()).stream()
                .map(
                        rs -> new Conversation(
                                profiles.profile(viewer, "u" + rs.getPeerId()),
                                message(viewer, rs),
                                rs.getUnreadCount()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return PageResult.of(q.getPageNum(), q.getPageSize(), total, rows);
    }

    private Unread unreadResult(Map<String, Object> row) {
        return new Unread(longValue(row, "unread_count"), text(row, "id"), text(row, "sender_id"));
    }

    // 【未读提醒】返回未读总数及最近一条未读消息信息，供消息入口显示提示。
    public Unread unread(long viewer) {
        profiles.requireUser(viewer);
        return unreadResult(mapper.selectUnread(viewer));
    }
}
