package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextDtos;

/**
 * 分片审计的只读查询（管理端排障用）。
 *
 * <p>写侧是 {@link ChatContextRecorder}（每轮落 1~5 行元数据），读侧就是这里——两者分开是刻意的：
 * 写侧在对话链路上，必须无副作用；读侧会被反复调用，不能顺带写日志或改状态。
 */
public interface ChatContextQueryService {

    /**
     * 查某个会话的分片审计。
     *
     * @param sessionId 会话 ID，必须存在（不存在抛 404，与"存在但还没记录"区分开）
     * @param round     只看某一轮；为空则返回全部轮次
     */
    ChatContextDtos.ContextView view(Long sessionId, Integer round);
}
