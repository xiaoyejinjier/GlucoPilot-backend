package com.threadfit.glucopilot.common.mq.job;

/**
 * 某一类后台任务的处理器。
 *
 * <h2>为什么拆成 {@code claim} + {@code handle} 两步</h2>
 * 消息至少会被投递一次（补扫会和 MQ 投递重叠、重投也会重复），所以「谁能处理」这件事
 * 必须由**状态机**回答，而不是由 MQ 的投递次数回答。{@code claim} 就是那次条件更新：
 * 它返回 true 表示"这条任务现在归我"，返回 false 表示"别人已经认领 / 已经办完 / 状态不允许"，
 * 后者是**正常路径**（消息要 ack），不是错误。
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>{@code claim} 必须是幂等的条件更新（{@code WHERE id=? AND status=?} 这类），
 *       不能"先查再改"；</li>
 *   <li>{@code handle} 里不要自己 ack/nack、不要自己 sleep 重试——失败就返回
 *       {@link JobOutcome#RETRYABLE}，投递策略由重投器统一决定；</li>
 *   <li>处理器抛出的任何未捕获异常都会被当成 {@code RETRYABLE}（见 {@code JobDispatcher}），
 *       因为"未知异常"更像是环境问题而不是内容问题。</li>
 * </ul>
 */
public interface JobHandler {

    /** 本处理器负责的任务类型，取值见 {@link JobTypes}。 */
    String type();

    /**
     * 认领任务（条件更新）。
     *
     * @return true 表示本次由我处理；false 表示已被处理过或状态不允许（消息应直接 ack）
     */
    boolean claim(JobEnvelope envelope);

    /** 真正干活，返回三态之一。 */
    JobOutcome handle(JobEnvelope envelope);
}
