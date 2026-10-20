package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatMessage;

/**
 * 会话消息写入与历史读取契约（接口 2.24 + 契约外补充的"会话内历史"）。
 *
 * <h2>幂等键</h2>
 * 用户消息：客户端 clientMsgId；助手消息：确定性的 assistant:&lt;replyToMsgId&gt;。
 * 两者共用唯一索引 uk_session_client_active(session_id, client_msg_id, is_deleted)。
 * 助手消息必须带这个键，否则「同一轮只落一条回复」就只剩普通快照读，两个并发请求会各自落一条。
 *
 * <h2>历史读取与"记忆窗口"刻意不同</h2>
 * 这里返回<b>全部历史</b>（用户能往上翻到很久以前），与送进模型的最近一段无关；
 * 界面上出现"模型不记得但我明明看得见"，根因就在这个区分上，不是 bug。
 */
public interface ChatMessageService {

    /** 2.24 写入用户消息（clientMsgId 幂等）。 */
    ChatDtos.SendMessageResponse appendUserMessage(Long userId, Long sessionId, ChatDtos.SendMessageRequest request);

    /** 会话内历史消息，按 seq_no 倒序取一页再反转成正序；beforeSeq 传上一页最早那条。 */
    ChatDtos.HistoryResponse readHistory(Long userId, Long sessionId, Integer pageSize, Integer beforeSeq);

    /**
     * 写入助手回复（流结束后调用）。
     * <p>
     * 在响应式回调线程上执行，取不到 RequestContext（ThreadLocal），因此审计字段显式写 userId。
     */
    ChatMessage appendAssistantMessage(Long userId, Long sessionId, Long replyToMsgId, String contentText,
                                       String modelCode, Integer promptTokens, Integer completionTokens,
                                       Integer finishReason, Integer latencyMs);
}