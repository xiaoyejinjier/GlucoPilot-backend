package com.threadfit.glucopilot.common.mq.job;

/**
 * 一次任务处理的结论，决定消息被 ack 还是进死信。
 *
 * <h2>为什么只有三态</h2>
 * 消费端能给出的判断其实只有三种：办成了、这次办不成但可能下次能成、永远办不成。
 * 划分过细（"重试 1 次还是 2 次"、"退避多久"）会把投递策略泄漏进业务处理器，
 * 反而让每个 handler 都要懂 MQ；所以延迟与次数统一由 {@code JobRetryReplayer} 管。
 *
 * <h2>为什么没有"稍后再放回队列"</h2>
 * {@code basicNack(requeue=true)} 会把消息放回队头，一条坏消息能堵住整个队列
 * （后面的任务明明能处理却出不来）。所以"暂时办不成"一律走死信 + 延迟重投。
 */
public enum JobOutcome {

    /** 办成了（或已经被别人办完）：ack，不做任何事。 */
    SUCCESS,

    /** 暂时办不成（上游超时、限流、网络抖动）：进死信队列，稍后由重投器再发。 */
    RETRYABLE,

    /** 永远办不成（内容违规判定、参数非法、对象已删）：ack 并落终态，不再重投。 */
    PERMANENT
}
