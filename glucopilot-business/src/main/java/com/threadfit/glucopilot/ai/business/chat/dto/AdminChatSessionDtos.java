package com.threadfit.glucopilot.ai.business.chat.dto;

import java.time.LocalDateTime;

/**
 * 管理端「AI 会话审计」的出入参。
 *
 * <h2>为什么列表只给 preview（≤200 字）而不是正文</h2>
 * 一页 20 个会话、每个几十条消息，如果列表就带全文，等于把「翻页」变成「批量读用户隐私」——
 * 运营只是想知道"这轮为什么答得差"，不需要顺手把整段问诊拉走。所以：
 * <ul>
 *   <li>{@code messages()} 只回 {@code preview}（截断后的摘要，用于认位置）；</li>
 *   <li>看全文必须点开单条，走 {@code messageDetail()}，那是一次<b>显式</b>的读取，
 *       并且会写一条 {@code ops_audit_log} 留痕。</li>
 * </ul>
 *
 * <h2>为什么 {@code contextRounds} 在这里而不在 message 里</h2>
 * 轮次数量是「这一会话被记录了几轮 prompt 装配」，来自 {@code chat_context} 的
 * {@code COUNT(DISTINCT context_round)}；它帮运营一眼看出"哪些会话有分片可查"——
 * 没有分片的会话（例如 ③ 期之前的历史会话）点进去只能看原文，看不了 token 构成。
 */
public final class AdminChatSessionDtos {

    private AdminChatSessionDtos() {
    }

    /**
     * 会话列表的一行。
     *
     * @param hasSummary    是否已有滚动摘要（{@code chat_session.context_summary} 非空）
     * @param contextRounds 该会话在 {@code chat_context} 里有几轮分片；0 表示没有可查的 token 构成
     */
    public record SessionRow(
            long id,
            long userId,
            String userNickname,
            String title,
            int sessionStatus,
            int sessionType,
            String modelCode,
            String systemPromptVer,
            int msgCount,
            Integer contextRounds,
            boolean hasSummary,
            LocalDateTime lastMsgAt,
            LocalDateTime createdAt) {
    }

    /**
     * 会话消息的一条（预览态）。
     *
     * @param preview 已截断的正文摘要：优先 {@code content_text}，其次图片描述、语音转写；
     *                都为空的图片/语音消息给占位文案（{@code [图片]} / {@code [语音]}）
     */
    public record MessageRow(
            long id,
            int seqNo,
            int msgRole,
            int msgType,
            String preview,
            String modelCode,
            Integer promptTokens,
            Integer completionTokens,
            Integer latencyMs,
            Integer finishReason,
            LocalDateTime createdAt) {
    }

    /** 单条消息的全文（必须显式请求，写入审计）。 */
    public record MessageDetail(
            long id,
            long sessionId,
            long userId,
            int seqNo,
            int msgRole,
            int msgType,
            String contentText,
            String mediaCaption,
            String asrText,
            Long mediaId,
            Long voiceMediaId,
            Integer voiceDurationMs,
            Long replyToMsgId,
            String modelCode,
            Integer promptTokens,
            Integer completionTokens,
            Integer latencyMs,
            Integer finishReason,
            LocalDateTime createdAt) {
    }
}
