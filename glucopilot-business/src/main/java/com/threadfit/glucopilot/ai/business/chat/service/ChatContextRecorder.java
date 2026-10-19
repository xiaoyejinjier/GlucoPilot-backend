package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.memory.assembly.ChatContextShard;

import java.util.List;

/**
 * 一轮 prompt 的分片元数据落库契约（③ 期）。
 *
 * <h2>为什么单独一个契约，而不是塞进补全服务</h2>
 * 落库时机很讲究（必须在助手消息 INSERT 之后，才有 assistant_msg_id），而补全服务的收尾回调本来就长；
 * 把「五行 INSERT + 失败降级」搬出去，主链路读起来仍然是「聚合 → 落库 → done」。
 *
 * <h2>失败只 WARN</h2>
 * 分片是旁路观测（用途是排障与成本分析），绝不是「这一轮回复合不合法」的判据。
 * 表写不进去时回复已经成功发出去了；若因为观测失败让这一轮变成错误，
 * 就变成用次要目标否决主要目标。
 */
public interface ChatContextRecorder {

    /**
     * 写入一轮的分片元数据。
     *
     * @param userId         当前用户
     * @param sessionId      会话
     * @param triggerMsgId   本轮触发的用户消息，可为 null
     * @param assistantMsgId 本轮落库后的助手消息（<b>必须</b>是落库后拿到的 ID，用于回填）
     * @param modelCode      本轮模型编码，可空
     * @param contextRound   会话内轮次号（null/&lt;1 按 1）
     * @param shards         装配阶段算出的分片（空表示无内容可记，直接跳过）
     */
    void record(Long userId, Long sessionId, Long triggerMsgId, Long assistantMsgId,
                String modelCode, Integer contextRound, List<ChatContextShard> shards);

    /**
     * 轮次号的唯一推导规则：由助手消息的 seq_no 反推（一轮 = 用户 + 助手两条）。
     * <p>
     * 用 (seq + 1) / 2 而不是查 chat_session.msg_count：后者是冗余列，在并发与幂等重放时可能滞后；
     * 而 seq_no 是这一轮自己那条消息上的确定值，落库后立刻可用，不需要额外一次查询。
     * <p>
     * 静态方法：接口静态方法不会被实现类继承，调用方请写全限定名。
     */
    static Integer roundOf(Integer assistantSeqNo) {
        if (assistantSeqNo == null || assistantSeqNo < 1) {
            return 1;
        }
        return (assistantSeqNo + 1) / 2;
    }
}