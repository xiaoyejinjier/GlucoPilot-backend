package com.threadfit.glucopilot.common.mq.job;

import java.time.Instant;

/**
 * 任务消息的统一信封。
 *
 * <h2>为什么只放标识，不放内容</h2>
 * 审核要审正文、记忆抽取要读会话、OCR 要读图片：这些内容都在数据库里且可能变。
 * 把正文塞进消息会带来三个问题——用户内容进了 MQ（合规面变大）、消费时可能已经过时
 * （"改稿后审旧文本"）、消息体膨胀后 DLQ 也难排查。所以这里只有 {@code refId}，
 * 消费端按 id 回库取**当前**内容，并用 {@code revision}（审核专用）做乐观并发。
 *
 * <h2>为什么 {@code jobId} 要同时写进消息头</h2>
 * 消息头 {@code messageId} 与管理台的「消息详情」直接可查，出问题能用同一个字符串
 * 把「业务日志 / MQ 管理台 / 死信」三处对起来；本类里也保留一份，重投时不变。
 *
 * <h2>{@code attempt} 的语义</h2>
 * 是**已经失败过的次数**，不是"第几次投递"：首次发送恒为 0，每次从死信重投 +1，
 * 到 {@code maxAttempts} 就不再重投（留在 DLQ 并由人处置）。之所以不由 broker 记，
 * 是因为死信队列里没有可靠的"第几次"信息（{@code x-death} 只在真正死信时加一条）。
 */
public record JobEnvelope(
        String jobId,
        String type,
        Long refId,
        Integer revision,
        Long userId,
        String reason,
        int attempt,
        String createdAt) {

    /** 新建一个待投递的任务（{@code attempt = 0}）。 */
    public static JobEnvelope of(String type, Long refId, Integer revision, Long userId, String reason) {
        return new JobEnvelope(jobIdOf(type, refId, revision), type, refId, revision, userId, reason, 0,
                Instant.now().toString());
    }

    /**
     * 生成幂等可读的任务标识，例如 {@code MODERATION_ARTICLE:123:r2}。
     *
     * <p>带上 {@code revision} 是为了让"同一篇文章的两次审核"在日志里可区分；
     * 没有 revision 的任务（记忆、OCR）退化为 {@code TYPE:refId}。
     */
    public static String jobIdOf(String type, Long refId, Integer revision) {
        String base = type + ":" + refId;
        return revision == null ? base : base + ":r" + revision;
    }

    /** 失败重投时递增次数，其余字段原样保留。 */
    public JobEnvelope withAttempt(int nextAttempt) {
        return new JobEnvelope(jobId, type, refId, revision, userId, reason, nextAttempt, createdAt);
    }

    /** 路由键由类型推导，避免发送方自己拼字符串拼错。 */
    public String routingKey() {
        return JobTypes.routingKeyOf(type);
    }
}
