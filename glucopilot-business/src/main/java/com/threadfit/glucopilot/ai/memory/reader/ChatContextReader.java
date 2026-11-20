package com.threadfit.glucopilot.ai.memory.reader;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatToolCall;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatMessageMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatToolCallMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.MessageGap;
import com.threadfit.glucopilot.ai.business.chat.mapper.SummaryState;
import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;

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

    /** 必留带条数要用到「指针之后还有几条消息」，只能回到 chat_message 数。 */
    private final ChatMessageMapper chatMessageMapper;

    private final AIProperties aiProperties;

    /** ③ 期：长期记忆读取（用户维度，与 sessionId 无关）。 */
    private final UserChatMemoryService userMemoryService;

    /** 滚动摘要；没有或读失败返回 null。 */
    public String summary(Long sessionId) {
        SummaryState state = summaryState(sessionId);
        return state == null ? null : state.getText();
    }

    /**
     * 滚动摘要 + 它覆盖到哪一条消息（③ 期）。
     * <p>
     * 装配日志要同时打「摘要多少字」和「覆盖到 #几」：只看"有没有摘要"无法判断摘要够不够用——
     * 覆盖位置与窗口起点之间的缝隙（空洞）才是"模型记不记得住前面聊了什么"的真正指标。
     * <p>
     * 读失败返回 null（记忆是增强项，不能让它把这一轮问答带崩）。
     */
    public SummaryState summaryState(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            SummaryState state = chatSessionMapper.selectSummaryState(sessionId);
            if (state == null || !StringUtils.hasText(state.getText())) {
                return null;
            }
            state.setText(state.getText().trim());
            return state;
        } catch (RuntimeException e) {
            log.warn("读取会话摘要失败（本轮不使用摘要）sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 摘要指针之后还有多少条可回放消息 —— 装配器的「必留带」条数（2026-11-19）。
     *
     * <p>为什么必须算出来再告诉装配器：摘要永远不覆盖最近 {@code keep-recent-messages} 条，
     * 所以窗口一旦滑过指针，那几条就既不进摘要也不进窗口（日志里的「空洞」），模型直接断片。
     * 把条数交给装配器，就能让它"必留带不受条数上限约束"，空洞不再取决于摘要跑得快不快。
     *
     * <p>读失败返回 0（退回旧行为：只靠 keep-recent 保底），并留一条 WARN。
     */
    public int countUnsummarized(Long sessionId, Long uptoMsgId) {
        if (sessionId == null) {
            return 0;
        }
        try {
            return Math.max(chatMessageMapper.countUnsummarized(sessionId, uptoMsgId == null ? 0L : uptoMsgId), 0);
        } catch (RuntimeException e) {
            log.warn("统计未摘要消息条数失败（本轮退回只留保底条数）sessionId={} upto={}", sessionId, uptoMsgId, e);
            return 0;
        }
    }

    /**
     * 摘要与窗口之间的空洞（③ 期）：既没进摘要、也没进本轮窗口的消息条数与起点。
     * <p>
     * 只在装配时确实裁掉了历史（{@code dropped > 0}）才调用：没有裁剪说明窗口从会话开头就装下了，
     * 不可能有空洞，那一次查询就白花。读失败返回 null（日志里少两个字段，不影响回答）。
     */
    public MessageGap messageGap(Long sessionId, Long uptoMsgId, int dropped) {
        if (sessionId == null || uptoMsgId == null || dropped <= 0) {
            return null;
        }
        try {
            return chatSessionMapper.selectMessageGap(sessionId, uptoMsgId, dropped);
        } catch (RuntimeException e) {
            log.warn("统计摘要与窗口之间的空洞失败 sessionId={} upto={} dropped={}", sessionId, uptoMsgId, dropped, e);
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