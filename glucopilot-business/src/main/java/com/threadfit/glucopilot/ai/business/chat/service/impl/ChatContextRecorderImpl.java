package com.threadfit.glucopilot.ai.business.chat.service.impl;

import com.threadfit.glucopilot.ai.business.chat.entity.ChatContextEntity;
import com.threadfit.glucopilot.ai.business.chat.mapper.ChatContextMapper;
import com.threadfit.glucopilot.ai.memory.assembly.ChatContextShard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import com.threadfit.glucopilot.ai.business.chat.service.ChatCompletionService;
import com.threadfit.glucopilot.ai.business.chat.service.ChatContextRecorder;

/**
 * 把一轮 prompt 的分片元数据写进 {@code chat_context}（③ 期）。
 *
 * <h2>为什么单独一个组件，而不是塞进 ChatCompletionService</h2>
 * 落库时机很讲究（必须在助手消息 INSERT 之后，才有 {@code assistant_msg_id}），
 * 而 {@code ChatCompletionService} 的 {@code Mono} 回调本来就长；把"五行 INSERT + 失败降级"
 * 搬出去，主链路读起来仍然是"聚合 → 落库 → done"。另外它也能被单测直接驱动，
 * 不必为了验证"插了哪几行"去起一条完整的假模型流。
 *
 * <h2>失败只 WARN</h2>
 * 分片是<b>旁路观测</b>：它的用途是排障与成本分析，绝不是"这一轮回复合不合法"的判据。
 * 表写不进去时，回复已经成功发出去了，若因为观测失败让这一轮变成错误，
 * 就变成"用次要目标否决主要目标"——与 {@code OpsAuditRecorder}、{@code ToolCallTraceRecorder}
 * 是同一条纪律。整批失败就整批放弃，不做逐行重试：缺一次观测不影响业务。
 *
 * <h2>为什么用 {@code context_round} 而不是 {@code trigger_msg_id} 分组</h2>
 * 轮次号是稳定的分组键（同一轮的所有分片共用一个），而 {@code trigger_msg_id} 更适合做"点查"。
 * 轮次号由调用方按助手消息的 {@code seq_no} 推导（一轮 = 用户 + 助手两条消息），
 * 这里只做兜底（缺省 1），不重新查库——那会让每次回复多一次 COUNT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextRecorderImpl implements ChatContextRecorder {

    /** 助手消息的模型编码列是 varchar(64)：超长直接截断，不为了一个展示字段让插入失败。 */
    private static final int MODEL_CODE_MAX = 64;

    /** 分片键列是 varchar(64)。 */
    private static final int CONTEXT_KEY_MAX = 64;

    private final ChatContextMapper chatContextMapper;

    /**
     * 写入一轮的分片元数据。
     *
     * @param userId         当前用户
     * @param sessionId      会话
     * @param triggerMsgId   本轮触发的用户消息（可为 null：例如无状态调用）
     * @param assistantMsgId 本轮落库后的助手消息（<b>必须</b>是落库后拿到的 ID，用于回填）
     * @param modelCode      本轮模型编码，可空
     * @param contextRound   会话内轮次号（null/&lt;1 按 1）
     * @param shards         装配阶段算出的分片（空表示无内容可记，直接跳过）
     */
    public void record(Long userId, Long sessionId, Long triggerMsgId, Long assistantMsgId,
                       String modelCode, Integer contextRound, List<ChatContextShard> shards) {
        if (userId == null || sessionId == null || shards == null || shards.isEmpty()) {
            return;
        }
        int round = contextRound == null || contextRound < 1 ? 1 : contextRound;
        String model = truncate(modelCode, MODEL_CODE_MAX);
        try {
            for (ChatContextShard shard : shards) {
                if (shard == null) {
                    continue;
                }
                ChatContextEntity row = new ChatContextEntity();
                row.setSessionId(sessionId);
                row.setUserId(userId);
                row.setTriggerMsgId(triggerMsgId);
                row.setAssistantMsgId(assistantMsgId);
                row.setContextRound(round);
                row.setContextType(shard.contextType());
                row.setContextKey(truncate(shard.contextKey(), CONTEXT_KEY_MAX));
                // 两个内容列恒为 NULL（只记元数据）：见 ChatContextEntity 类注释与 V28 迁移
                row.setContentText(null);
                row.setContentJson(null);
                row.setTokenEstimate(Math.max(shard.tokenEstimate(), 0));
                row.setModelCode(model);
                row.setSortNo(shard.sortNo());
                // created_by / updated_by 由 MetaObjectHandler 统一填充（ops 优先、其次 C 端用户），
                // 但这条链路上拿不到 RequestContext（响应式回调线程），所以显式写 userId，
                // 否则会落成 0，分片行就无法回答"这一轮是谁的"
                row.setCreatedBy(userId);
                row.setUpdatedBy(userId);
                chatContextMapper.insert(row);
            }
            log.debug("分片元数据已落库 sessionId={} round={} 条数={} assistantMsgId={}",
                    sessionId, round, shards.size(), assistantMsgId);
        } catch (RuntimeException e) {
            log.warn("分片元数据落库失败（不影响本轮回复）sessionId={} round={} 条数={}",
                    sessionId, round, shards.size(), e);
        }
    }

    /**
     * 轮次号的唯一推导规则：由助手消息的 {@code seq_no} 反推（一轮 = 用户 + 助手两条）。
     * <p>
     * 用 {@code (seq + 1) / 2} 而不是查 {@code chat_session.msg_count}：后者是冗余列，
     * 在并发与幂等重放时可能滞后；而 {@code seq_no} 是这一轮自己那条消息上的确定值，
     * 落库后立刻可用，不需要额外一次查询。
     */
    public static Integer roundOf(Integer assistantSeqNo) {
        if (assistantSeqNo == null || assistantSeqNo < 1) {
            return 1;
        }
        return (assistantSeqNo + 1) / 2;
    }

    private static String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
