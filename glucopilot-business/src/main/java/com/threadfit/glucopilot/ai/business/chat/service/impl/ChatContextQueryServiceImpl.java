package com.threadfit.glucopilot.ai.business.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextDtos;
import com.threadfit.glucopilot.ai.business.chat.dto.ChatContextShard;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatContextEntity;
import com.threadfit.glucopilot.ai.business.chat.entity.ChatSession;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.business.chat.service.ChatContextQueryService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片审计只读实现：一条 {@code selectList} + 内存分组。
 *
 * <h2>为什么用 MyBatis-Plus 的 CRUD 而不是手写聚合 SQL</h2>
 * 数据量是"每轮 1~5 行"，一个会话最多几百行——手写 {@code GROUP BY} 只会多一处"改了实体忘了改
 * SQL"的机会，而内存分组还能顺手给出稳定顺序（{@code context_round, sort_no}）。
 *
 * <h2>为什么先用 {@code selectById} 判会话存在</h2>
 * {@code chat_context} 是 ③ 期才开始写入的，老会话没有任何分片是正常状态。如果直接把"查不到分片"
 * 当成错误，排障的人会以为是接口坏了；反过来，会话 ID 随便填一个也能返回空列表，又会让调用方
 * 以为"这个会话确实没记录"。所以分开：会话不存在 → 404，存在但无记录 → 空列表。
 *
 * <p>注意 {@code ChatSessionMapper} 的 {@code selectById} 带 {@code @TableLogic}，逻辑删掉的会话
 * 会返回 null——这正是我们要的语义（已删除的会话不该还能查审计）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextQueryServiceImpl implements ChatContextQueryService {

    private final ChatContextMapper contextMapper;
    private final ChatSessionMapper sessionMapper;

    @Override
    public ChatContextDtos.ContextView view(Long sessionId, Integer round) {
        if (sessionId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话 ID 不能为空");
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在或已删除：" + sessionId);
        }
        LambdaQueryWrapper<ChatContextEntity> query = new LambdaQueryWrapper<ChatContextEntity>()
                .eq(ChatContextEntity::getSessionId, sessionId)
                .orderByAsc(ChatContextEntity::getContextRound)
                .orderByAsc(ChatContextEntity::getSortNo);
        if (round != null) {
            query.eq(ChatContextEntity::getContextRound, round);
        }
        List<ChatContextEntity> rows = contextMapper.selectList(query);
        if (rows == null || rows.isEmpty()) {
            return new ChatContextDtos.ContextView(sessionId, List.of(), 0L);
        }

        // LinkedHashMap 保证轮次顺序与 SQL 的 ORDER BY 一致；查出来的行已按 (round, sort_no) 排好
        Map<Integer, List<ChatContextEntity>> byRound = new LinkedHashMap<>();
        for (ChatContextEntity row : rows) {
            Integer key = row.getContextRound() == null ? 0 : row.getContextRound();
            byRound.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<ChatContextDtos.ContextRound> rounds = new ArrayList<>(byRound.size());
        long total = 0;
        for (Map.Entry<Integer, List<ChatContextEntity>> entry : byRound.entrySet()) {
            List<ChatContextEntity> shards = entry.getValue();
            ChatContextEntity head = shards.get(0);
            List<ChatContextDtos.Shard> shardDtos = new ArrayList<>(shards.size());
            int roundTokens = 0;
            int historyShards = 0;
            boolean shifted = false;
            Integer windowMessages = null;
            for (ChatContextEntity shard : shards) {
                int tokens = shard.getTokenEstimate() == null ? 0 : shard.getTokenEstimate();
                roundTokens += tokens;
                shardDtos.add(new ChatContextDtos.Shard(shard.getContextType(), shard.getContextKey(),
                        shard.getTokenEstimate(), shard.getSortNo(),
                        shard.getWindowShifted(), shard.getWindowMessages()));
                if (ChatContextShard.KEY_HISTORY_MESSAGES.equals(shard.getContextKey())) {
                    historyShards++;
                    shifted = Boolean.TRUE.equals(shard.getWindowShifted());
                    windowMessages = shard.getWindowMessages();
                }
            }
            total += roundTokens;
            String notice = describeNotice(historyShards, shifted, windowMessages);
            rounds.add(new ChatContextDtos.ContextRound(entry.getKey(), head.getTriggerMsgId(),
                    head.getAssistantMsgId(), head.getModelCode(), roundTokens, shardDtos, notice));
        }
        log.debug("查询分片审计 sessionId={} round={} 轮次={} token合计={}", sessionId, round, rounds.size(), total);
        return new ChatContextDtos.ContextView(sessionId, rounds, total);
    }

    /**
     * 这一轮的一句话说明。
     *
     * <p>为什么由后端拼：判断它需要同时理解 {@code dropped_history}（在日志里）与
     * {@code window_shifted / window_messages}（在表里）的关系，把这段逻辑复制到前端，
     * 一旦后端改了装配语义，页面就会开始撒谎。而「本轮完整回放」这种事实，前端也无从推断。
     */
    private static String describeNotice(int historyShards, boolean shifted, Integer windowMessages) {
        if (historyShards <= 0) {
            return "本轮没有回放历史消息";
        }
        if (shifted) {
            return windowMessages == null
                    ? "本轮窗口已滑动（丢弃了窗口外的旧消息）"
                    : "本轮窗口已滑动 · 历史 " + windowMessages + " 条";
        }
        return windowMessages == null ? "本轮完整回放" : "本轮完整回放 · 历史 " + windowMessages + " 条";
    }
}
