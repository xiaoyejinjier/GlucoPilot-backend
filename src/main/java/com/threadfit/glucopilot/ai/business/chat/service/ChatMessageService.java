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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户消息写入（对应接口 2.24）。
 * 幂等键：chat_message 唯一索引 uk_session_client_active(session_id, client_msg_id, is_deleted)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionService chatSessionService;
    private final OwnedMediaResolver ownedMediaResolver;

    @Transactional
    public ChatDtos.SendMessageResponse appendUserMessage(Long userId, Long sessionId, ChatDtos.SendMessageRequest request) {
        // 归属校验：不属于当前用户按 404 处理
        chatSessionService.requireOwned(userId, sessionId);
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
        message.setSeqNo(chatMessageMapper.selectNextSeqNo(sessionId));

        try {
            chatMessageMapper.insert(message);
        } catch (DuplicateKeyException e) {
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

    /**
     * 写入助手回复（流结束后调用）。
     * <p>
     * 注意：该方法在响应式回调线程上执行，{@code RequestContext}（ThreadLocal）取不到当前用户，
     * 因此审计字段显式写入 userId，避免 created_by 落成 0。
     * <p>
     * {@code client_msg_id} 留空：唯一键是 (session_id, client_msg_id)，若与用户消息共用同一个
     * clientMsgId 会直接冲突；助手消息的幂等改由 {@code reply_to_msg_id} 判定。
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
        message.setModelCode(modelCode);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setFinishReason(finishReason);
        message.setLatencyMs(latencyMs);
        message.setSeqNo(chatMessageMapper.selectNextSeqNo(sessionId));
        message.setCreatedBy(userId);
        message.setUpdatedBy(userId);
        chatMessageMapper.insert(message);

        chatSessionMapper.touchOnMessage(sessionId, LocalDateTime.now());
        return message;
    }
}
