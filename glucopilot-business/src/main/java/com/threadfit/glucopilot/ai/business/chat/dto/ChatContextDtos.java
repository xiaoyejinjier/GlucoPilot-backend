package com.threadfit.glucopilot.ai.business.chat.dto;

import java.util.List;

/**
 * 「这一轮模型到底拿到了什么」的只读视图（对应 {@code chat_context} 的分片元数据）。
 *
 * <p>为什么只给元数据、不给正文：{@code chat_context.content_text/content_json} 恒为 NULL，
 * 正文在 {@code chat_message}、摘要在 {@code chat_session.context_summary}。查这个接口是为了
 * 回答"预算花在哪、历史裁掉了几条、摘要/工具摘要有没有生效"，不是为了看用户的聊天内容。
 * 需要原文时用 {@link ContextRound#triggerMsgId()} / {@link ContextRound#assistantMsgId()}
 * 回查消息表——这样用户删除对话时，这些审计数据不会成为一条绕过删除权的副本。
 */
public final class ChatContextDtos {

    private ChatContextDtos() {
    }

    /** 一个分片：类型 + 键 + 估算 token + 顺序。 */
    public record Shard(Integer contextType, String contextKey, Integer tokenEstimate, Integer sortNo) {
    }

    /**
     * 一轮 prompt 的分片组成。
     *
     * @param contextRound   会话内轮次号（从 1 起）
     * @param triggerMsgId   触发本轮的用户消息 ID；历史数据可能为空
     * @param assistantMsgId 本轮生成的助手消息 ID；装配时未知，落库后回填
     * @param modelCode      本轮调用的模型编码
     * @param totalTokens    本轮各分片估算 token 之和（不是厂商返回的 usage，是装配期估算）
     */
    public record ContextRound(Integer contextRound, Long triggerMsgId, Long assistantMsgId,
                               String modelCode, int totalTokens, List<Shard> shards) {
    }

    /**
     * 一个会话的分片审计。
     *
     * @param sessionId   会话 ID
     * @param rounds      按轮次升序；没有任何记录时为空列表（不是错误：老会话或未跑完的轮次本就没有）
     * @param totalTokens 所有轮次之和，便于一眼看出"这个会话一共喂了多少上下文"
     */
    public record ContextView(Long sessionId, List<ContextRound> rounds, long totalTokens) {
    }
}
