package com.threadfit.glucopilot.ai.tool;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用元数据落库（B3）。
 * <p>
 * <b>原则：记账失败绝不影响工具本身。</b>工具调用是用户请求链路的一部分，而记元数据只是可观测性；
 * 让一次"写审计表失败"把本来能答的问题变成错误，是把次要目标放在了主要目标前面。
 * 因此这里所有异常都只 WARN。
 * <p>
 * 记录时机是<b>调用发生的当下</b>（不是流结束后统一写）：调用发生过是事实，
 * 哪怕用户中途取消、哪怕这一轮最终报错——那正是最需要留下痕迹的时候。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallTraceRecorder {

    private final ChatToolCallMapper chatToolCallMapper;

    private final ToolResultDigester digester;

    private final AIProperties aiProperties;

    /**
     * 记一条工具调用。
     *
     * @param toolContext 本轮工具上下文（缺失 sessionId/userMsgId 时静默跳过）
     * @param toolName    工具名
     * @param ok          是否成功
     * @param durationMs  耗时毫秒
     * @param errorType   失败时的异常类名，成功时为 null
     */
    public void record(ToolContext toolContext, String toolName, boolean ok, long durationMs, String errorType) {
        record(toolContext, toolName, ok, durationMs, errorType, null);
    }

    /**
     * 记一条工具调用（带结果摘要）。
     *
     * @param toolContext  本轮工具上下文（缺失 sessionId/userMsgId 时静默跳过）
     * @param toolName     工具名
     * @param ok           是否成功
     * @param durationMs   耗时毫秒
     * @param errorType    失败时的异常类名，成功时为 null
     * @param toolResult   工具返回原文；只有成功时才会被压缩成摘要落库（失败没有结果可记）
     */
    public void record(ToolContext toolContext, String toolName, boolean ok, long durationMs, String errorType,
                       String toolResult) {
        Long sessionId = AiToolContext.optionalLong(toolContext, AiToolContext.SESSION_ID);
        Long userMsgId = AiToolContext.optionalLong(toolContext, AiToolContext.USER_MSG_ID);
        if (sessionId == null) {
            // 没有会话上下文（单测/live 测试直接调工具等）：不记，也不报警——这不是异常情况
            log.debug("工具调用缺少 sessionId，跳过元数据登记：tool={}", toolName);
            return;
        }
        try {
            ChatToolCall row = new ChatToolCall();
            row.setSessionId(sessionId);
            row.setUserId(AiToolContext.optionalLong(toolContext, AiToolContext.USER_ID));
            row.setUserMsgId(userMsgId);
            row.setRoundKey(userMsgId == null ? null : "chat-round:" + userMsgId);
            row.setToolName(toolName);
            row.setCallIndex(nextCallIndex(toolContext));
            row.setOk(ok ? 1 : 0);
            row.setDurationMs((int) Math.min(Math.max(durationMs, 0), Integer.MAX_VALUE));
            row.setErrorType(errorType);
            if (ok) {
                row.setResultDigest(digester.digest(toolResult, aiProperties.getMemory().getToolDigestMaxChars()));
            }
            chatToolCallMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("工具调用元数据登记失败（不影响工具执行）：tool={} sessionId={}", toolName, sessionId, e);
        }
    }

    /**
     * 本轮内的调用序号：计数器放在 toolContext 里（见 {@link AiToolContext#CALL_COUNTER}），
     * 因此天然按轮隔离、无需清理。外层没放计数器时退回 1——
     * 序号只是给排查用的相对顺序，不值得为它让调用链变复杂。
     */
    private static int nextCallIndex(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return 1;
        }
        Object counter = toolContext.getContext().get(AiToolContext.CALL_COUNTER);
        if (counter instanceof AtomicInteger atomic) {
            return atomic.incrementAndGet();
        }
        return 1;
    }
}
