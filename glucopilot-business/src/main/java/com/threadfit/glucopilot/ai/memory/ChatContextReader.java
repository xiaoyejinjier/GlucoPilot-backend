package com.threadfit.glucopilot.ai.memory;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import com.threadfit.glucopilot.ai.config.AIProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 装配上下文所需的"会话附加信息"读取口（② 期）：滚动摘要 + 最近工具结果摘要。
 * <p>
 * 单独抽出来是为了让 {@link ChatMemoryReadAdvisor} 只关心"怎么拼消息"，
 * 以及让"读不到时怎么办"只有一处实现——这两件事都属于易错点。
 * <p>
 * <b>读不到一律降级为"没有"</b>（返回 null/空列表并记日志），绝不抛异常：
 * 记忆是增强项，附件读失败不应该让用户这一轮问答直接失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatContextReader {

    private final ChatSessionMapper chatSessionMapper;

    private final ChatToolCallMapper chatToolCallMapper;

    private final AIProperties aiProperties;

    /** ③ 期：长期记忆读取（用户维度，与 sessionId 无关）。 */
    private final UserChatMemoryService userMemoryService;

    /** 滚动摘要；没有或读失败返回 null。 */
    public String summary(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            String summary = chatSessionMapper.selectContextSummary(sessionId);
            return StringUtils.hasText(summary) ? summary.trim() : null;
        } catch (RuntimeException e) {
            log.warn("读取会话摘要失败（本轮不使用摘要）sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 最近工具结果摘要（时间正序，最老的在前）。
     * <p>
     * 关掉回放（{@code ai.memory.tool-replay-enabled=false}）时直接返回空列表，
     * 连一次查询都不发。
     */
    public List<String> recentToolDigests(Long sessionId) {
        AIProperties.MemoryProperties memory = aiProperties.getMemory();
        if (sessionId == null || !memory.isToolReplayEnabled() || memory.getToolReplayMaxItems() <= 0) {
            return List.of();
        }
        try {
            List<ChatToolCall> rows = chatToolCallMapper.selectRecentDigests(sessionId, memory.getToolReplayMaxItems());
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<String> digests = new ArrayList<>(rows.size());
            // 查询是倒序（取最近 N 条），反转成时间正序，让模型看到的顺序与实际发生顺序一致
            for (int i = rows.size() - 1; i >= 0; i--) {
                ChatToolCall row = rows.get(i);
                if (StringUtils.hasText(row.getResultDigest())) {
                    digests.add(row.getToolName() + " → " + row.getResultDigest().trim());
                }
            }
            return Collections.unmodifiableList(digests);
        } catch (RuntimeException e) {
            log.warn("读取工具结果摘要失败（本轮不注入）sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** 该用户的长期记忆（③ 期）：按置信度取前 N 条；读失败返回空列表（记忆是增强项）。 */
    public java.util.List<UserChatMemoryService.MemoryItem> longTermMemory(Long userId) {
        return userMemoryService.forInjection(userId);
    }
}