package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.support.OwnedMediaResolver;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ai.support.SqlDuplicateKeys;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.service.MediaFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户消息写入（对应接口 2.24）。
 * 幂等键：chat_message 唯一索引 uk_session_client_active(session_id, client_msg_id, is_deleted)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    /**
     * 助手消息的 client_msg_id 前缀。
     * <p>
     * <b>为什么要给助手消息也写 client_msg_id</b>：{@code uk_session_client_active(session_id,
     * client_msg_id, is_deleted)} 是"同一轮只落一条回复"的唯一权威判据，但它对 NULL 不生效
     * （唯一索引允许多个 NULL），而助手消息原先一直是 NULL——于是两个并发的 /completions
     * 都能通过 {@code selectAssistantReply} 的普通快照读，各自调一次模型、各落一条回复
     * （实测：prompt_tokens 5855 + 5940，两条 seq_no 都是 2）。
     * <p>
     * 用一个<b>确定性</b>的值（{@code assistant:<replyToMsgId>}）就复用上现成的唯一键，
     * 落败的一方捕获 1062 后回查既有回复返回，两个客户端最终看到同一条回答。
     * 前缀不能省：客户端生成的 clientMsgId 是 UUID，不会与之相撞；若直接复用用户消息的
     * clientMsgId 反而会与那条用户消息本身冲突。
     */
    private static final String ASSISTANT_CLIENT_MSG_ID_PREFIX = "assistant:";

    /** seq_no 撞唯一键后的重试次数：窗口是"两条 SQL 之间"的毫秒级，1 次重试即可覆盖正常并发。 */
    private static final int SEQ_NO_RETRY = 3;

    /** 历史消息每页条数（默认与上限）。 */
    private static final int DEFAULT_HISTORY_PAGE_SIZE = 20;
    private static final int MAX_HISTORY_PAGE_SIZE = 100;

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionService chatSessionService;
    private final OwnedMediaResolver ownedMediaResolver;
    private final MediaFileService mediaFileService;

    @Transactional
    public ChatDtos.SendMessageResponse appendUserMessage(Long userId, Long sessionId, ChatDtos.SendMessageRequest request) {
        // 归属校验 + 状态校验：不属于当前用户按 404；已结束/已归档的会话不能继续写（见 requireActive 注释）
        chatSessionService.requireActive(userId, sessionId);
        validatePayload(request);
        requireOwnedMedia(userId, request);

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setMsgRole(CodeEnum.ChatMsgRole.USER.getCode());
        message.setMsgType(request.getMsgType());
        message.setContentText(request.getContentText());
        message.setMediaId(request.getMediaId());
        message.setVoiceMediaId(request.getVoiceMediaId());
        message.setClientMsgId(request.getClientMsgId());

        try {
            insertWithSeqNo(message);
        } catch (DuplicateKeyException e) {
            // 撞的是顺序号唯一键：说明重试次数已耗尽，这是真冲突，不能当作"重复提交"
            if (SqlDuplicateKeys.isDuplicateKeyFor(e, ChatMessageMapper.UK_SESSION_SEQ)) {
                throw e;
            }
            // 重复 clientMsgId：当作成功，返回既有消息（不重复计数、不重复扣费）
            if (!SqlDuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            log.debug("clientMsgId 重复，返回既有消息 sessionId={} clientMsgId={}", sessionId, request.getClientMsgId());
            ChatMessage existing = chatMessageMapper.selectByClientMsgId(sessionId, request.getClientMsgId());
            if (existing == null) {
                throw e;
            }
            return toResponse(existing);
        }

        chatSessionMapper.touchOnMessage(sessionId, LocalDateTime.now());
        chatSessionService.renameIfDefault(sessionId, request.getContentText());
        return toResponse(message);
    }

    /** 图片/语音消息至少要带一个媒体 ID，否则前端传错会导致空消息入库。 */
    private static void validatePayload(ChatDtos.SendMessageRequest request) {
        int msgType = request.getMsgType();
        if (msgType == CodeEnum.ChatMsgType.TEXT.getCode()) {
            if (request.getContentText() == null || request.getContentText().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文本消息 contentText 不能为空");
            }
            return;
        }
        if (msgType == CodeEnum.ChatMsgType.IMAGE.getCode() && request.getMediaId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片消息 mediaId 不能为空");
        }
        if (msgType == CodeEnum.ChatMsgType.VOICE.getCode() && request.getVoiceMediaId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音消息 voiceMediaId 不能为空");
        }
    }

    /**
     * 媒体归属强校验：图片 / 语音消息引用的 mediaId 必须是<b>当前用户的、且业务类型匹配的、可用的</b>。
     * <p>
     * 为什么必须有这一步：{@link #validatePayload} 只校验了「图片消息 mediaId 不能为空」，
     * 没有校验这个 mediaId 归谁。缺了归属校验时，用户 A 可以传用户 B 的 mediaId——
     * 在只有文本的今天，后果仅仅是一条引用不到图的坏消息；但**接上视觉模型之后**，
     * 服务端会主动把 B 的图片读出来、描述给 A 看，从「坏引用」升级为实质性的越权内容泄露。
     * 所以这个校验必须在多模态能力上线之前就位，而不是跟着一起上。
     * <p>
     * 查询与文案统一由 {@link OwnedMediaResolver} 负责：2.25 在读图之前也要做同一件事，
     * 两处各写一份的话，将来只改一处就会留下「发消息被拒、但直接调补全却读得到」的半修状态。
     */
    private void requireOwnedMedia(Long userId, ChatDtos.SendMessageRequest request) {
        int msgType = request.getMsgType();
        if (msgType == CodeEnum.ChatMsgType.IMAGE.getCode()) {
            ownedMediaResolver.require(request.getMediaId(), userId, CodeEnum.MediaBizType.CHAT_IMAGE);
        } else if (msgType == CodeEnum.ChatMsgType.VOICE.getCode()) {
            ownedMediaResolver.require(request.getVoiceMediaId(), userId, CodeEnum.MediaBizType.CHAT_VOICE);
        }
        // 文本消息没有媒体引用，一次查询都不该发
    }

    private static ChatDtos.SendMessageResponse toResponse(ChatMessage message) {
        return ChatDtos.SendMessageResponse.builder()
                .id(message.getId())
                .seqNo(message.getSeqNo())
                .build();
    }

    // ------------------------------------------------------------------ 会话内历史读取

    /**
     * 读会话内历史消息（契约外补充接口；2.23~2.25 之外，UI 打开历史会话必需）。
     *
     * <h2>与"记忆窗口"的区别（务必别混）</h2>
     * 这里返回的是<b>全部历史</b>，与送进模型的最近 N 条（{@code ai.memory.window-messages}）无关：
     * 用户能在界面上往上翻到很久以前的对话，而模型只看得到最近一小段。两者刻意不同——
     * 让用户"看不到自己说过的话"是不可接受的，而把全部历史塞进 prompt 会烧钱且超上下文。
     * 界面上如果出现"模型不记得但我明明看得见"，根因就在这里，不是 bug。
     *
     * <h2>分页</h2>
     * 按 {@code seq_no} 倒序取 N 条再反转成正序返回（{@code beforeSeq} 传上一页最早那条的 seq_no），
     * 复用 {@code uk_session_seq(session_id, seq_no)} 索引；{@code hasMore} 用"多取一条"判断，
     * 不额外查总数（历史消息可能上万条，COUNT 没有意义）。
     */
    public ChatDtos.HistoryResponse readHistory(Long userId, Long sessionId, Integer pageSize, Integer beforeSeq) {
        chatSessionService.requireOwned(userId, sessionId);
        int limit = pageSize == null || pageSize <= 0 ? DEFAULT_HISTORY_PAGE_SIZE
                : Math.min(pageSize, MAX_HISTORY_PAGE_SIZE);
        int maxSeqNo = beforeSeq == null || beforeSeq <= 0 ? Integer.MAX_VALUE : beforeSeq - 1;

        // 多取一条用于判断"还有更早的"，返回前丢掉
        List<ChatMessage> rows = chatMessageMapper.selectRecent(sessionId, maxSeqNo, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }

        Map<Long, String> mediaUrls = mediaUrls(userId, rows);
        List<ChatDtos.HistoryMessage> records = new ArrayList<>(rows.size());
        // 查询是倒序，反转成正序给前端（对话要按时间从上到下渲染）
        for (int i = rows.size() - 1; i >= 0; i--) {
            ChatMessage row = rows.get(i);
            records.add(ChatDtos.HistoryMessage.builder()
                    .id(row.getId())
                    .seqNo(row.getSeqNo())
                    .msgRole(row.getMsgRole())
                    .msgType(row.getMsgType())
                    .contentText(row.getContentText())
                    .mediaId(row.getMediaId())
                    .mediaUrl(row.getMediaId() == null ? null : mediaUrls.get(row.getMediaId()))
                    .modelCode(row.getModelCode())
                    .finishReason(row.getFinishReason())
                    .createdAt(row.getGmtCreate())
                    .build());
        }
        Integer nextBeforeSeq = rows.isEmpty() ? null : rows.get(rows.size() - 1).getSeqNo();
        return ChatDtos.HistoryResponse.builder()
                .records(records)
                .hasMore(hasMore)
                .nextBeforeSeq(nextBeforeSeq)
                .build();
    }

    /**
     * 批量取本页图片消息的可访问地址。
     * <p>
     * 只取一次（{@code listOwnedByIds}），不做逐条查询：一页 20 条里可能有几张图，
     * 逐条查就是 N+1。取不到的（已删除/已过期）在响应里就是 null，前端按"图片已失效"渲染。
     */
    private Map<Long, String> mediaUrls(Long userId, List<ChatMessage> rows) {
        List<Long> mediaIds = rows.stream()
                .map(ChatMessage::getMediaId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> urls = new HashMap<>();
        for (MediaFile media : mediaFileService.listOwnedByIds(mediaIds, userId, CodeEnum.MediaBizType.CHAT_IMAGE)) {
            urls.put(media.getId(), media.getFileUrl());
        }
        return urls;
    }

    /**
     * 写入助手回复（流结束后调用）。
     * <p>
     * 注意：该方法在响应式回调线程上执行，{@code RequestContext}（ThreadLocal）取不到当前用户，
     * 因此审计字段显式写入 userId，避免 created_by 落成 0。
     * <p>
     * <b>幂等靠唯一键而不是靠调用方那次回查</b>：{@code client_msg_id} 写成
     * {@code assistant:<replyToMsgId>}，于是 {@code uk_session_client_active} 保证"一条用户消息
     * 至多一条助手回复"。并发的第二个请求会撞 1062，这里回查既有回复返回——
     * 与用户消息路径（重复提交当成功）是同一套语义，两个客户端最终收敛到同一条回答。
     * <p>
     * <b>注意它挡不住"重复调用模型"</b>：INSERT 发生在模型调用之后，重复的模型调用已经花掉了。
     * 防重复扣费要靠 {@code ChatRoundGuard} 在<b>调模型之前</b>拦截（见 ChatCompletionService）。
     */
    @Transactional
    public ChatMessage appendAssistantMessage(Long userId,
                                              Long sessionId,
                                              Long replyToMsgId,
                                              String contentText,
                                              String modelCode,
                                              Integer promptTokens,
                                              Integer completionTokens,
                                              Integer finishReason,
                                              Integer latencyMs) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setMsgRole(CodeEnum.ChatMsgRole.ASSISTANT.getCode());
        message.setMsgType(CodeEnum.ChatMsgType.TEXT.getCode());
        message.setContentText(contentText);
        message.setReplyToMsgId(replyToMsgId);
        if (replyToMsgId != null) {
            message.setClientMsgId(ASSISTANT_CLIENT_MSG_ID_PREFIX + replyToMsgId);
        }
        message.setModelCode(modelCode);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setFinishReason(finishReason);
        message.setLatencyMs(latencyMs);
        message.setCreatedBy(userId);
        message.setUpdatedBy(userId);

        try {
            insertWithSeqNo(message);
        } catch (DuplicateKeyException e) {
            if (replyToMsgId == null
                    || (!SqlDuplicateKeys.isDuplicateKeyFor(e, ChatMessageMapper.UK_SESSION_CLIENT)
                        && !SqlDuplicateKeys.isDuplicateKeyFor(e, ChatMessageMapper.UK_SESSION_SEQ))) {
                throw e;
            }
            ChatMessage existing = chatMessageMapper.selectAssistantReply(sessionId, replyToMsgId);
            if (existing == null) {
                throw e;
            }
            log.info("本轮已并发落库过回复，复用既有助手消息 sessionId={} replyToMsgId={} 既有MsgId={}",
                    sessionId, replyToMsgId, existing.getId());
            return existing;
        }

        chatSessionMapper.touchOnMessage(sessionId, LocalDateTime.now());
        return message;
    }

    /**
     * 分配会话内顺序号并插入；撞 {@code uk_session_seq} 时重算重试。
     * <p>
     * 为什么用"乐观重试"而不是加锁：顺序号的冲突窗口只有"取 MAX 到 INSERT"之间的毫秒，
     * 且失败方重算一次几乎必然成功（MAX 已经包含并发方刚插入的行）。加锁（无论行锁还是分布式锁）
     * 都要为一个展示用字段付出协调成本，不划算。
     * <p>
     * 「重算必然前进」依赖 {@code selectNextSeqNo} 不过滤 {@code is_deleted}——否则软删除最大号那条后，
     * 每次重算都会得到同一个号，重试永远失败。
     */
    private void insertWithSeqNo(ChatMessage message) {
        for (int attempt = 1; ; attempt++) {
            message.setSeqNo(chatMessageMapper.selectNextSeqNo(message.getSessionId()));
            try {
                chatMessageMapper.insert(message);
                return;
            } catch (DuplicateKeyException e) {
                if (!SqlDuplicateKeys.isDuplicateKeyFor(e, ChatMessageMapper.UK_SESSION_SEQ)) {
                    throw e;
                }
                if (attempt >= SEQ_NO_RETRY) {
                    log.warn("顺序号连续 {} 次冲突，放弃重试 sessionId={}", attempt, message.getSessionId());
                    throw e;
                }
                log.debug("顺序号冲突，重算后重试 sessionId={} attempt={}", message.getSessionId(), attempt);
            }
        }
    }
}
