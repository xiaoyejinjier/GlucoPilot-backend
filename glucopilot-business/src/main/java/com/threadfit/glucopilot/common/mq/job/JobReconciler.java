package com.threadfit.glucopilot.common.mq.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 补扫器：定期把「数据库里还等着办、但消息可能丢了」的任务重新投递一遍。
 *
 * <h2>为什么必须有它</h2>
 * 消息投递有四个会丢的窗口：①事务提交成功但 {@code afterCommit} 里发送失败；
 * ②broker 重启/磁盘满导致消息没落盘；③消费端 ack 前进程被杀、而 broker 又恰好丢了消息；
 * ④有人直接改库造出任务（运维脚本、数据修复）。前三个是"概率小但一定会发生"，
 * 第四个是"人一定会这么干"。补扫把这四条缝都盖上。
 *
 * <h2>为什么是 5 分钟、而不是 30 秒</h2>
 * 补扫是全表条件查询，频率过高会变成 DB 热点；而任务的"正常"延迟由 MQ 保证（亚秒级），
 * 补扫只处理异常情况——5 分钟的额外延迟完全可接受。批大小 200 同样是为了不把积压
 * 一次性灌进队列（灌进去也消费不过来，只会把 DLQ 撑大）。
 *
 * <h2>为什么不需要 outbox 表</h2>
 * outbox 的价值是"业务写与消息写在同一事务里"，代价是一张只增不减的表 + 一个清理器。
 * 我们的任务状态本来就落在业务表里（{@code ai_status=PENDING} 等），补扫可以直接把它们当 outbox，
 * 省掉一次建模与一份数据；代价是"最多晚 5 分钟"，而这个延迟对审核/记忆/OCR 都可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobReconciler {

    private final JobProperties properties;
    private final JobPublisher publisher;
    private final List<JobReconcileSource> sources;

    /** 定时补扫（默认 5 分钟一次）。 */
    @Scheduled(fixedDelayString = "${glucopilot.mq.jobs.reconcile-interval-seconds:300}",
            initialDelayString = "${glucopilot.mq.jobs.reconcile-initial-delay-seconds:60}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduledReconcile() {
        int published = reconcileOnce();
        if (published > 0) {
            log.info("补扫完成：投递 {} 条任务（来源 {} 个）", published, sources.size());
        }
    }

    /**
     * 执行一轮补扫（包级可见，供单测直接调用）。
     *
     * @return 成功投递的条数
     */
    int reconcileOnce() {
        if (!properties.isEnabled() || !properties.isReconcileEnabled()) {
            return 0;
        }
        int limit = Math.max(properties.getReconcileBatchSize(), 1);
        int published = 0;
        for (JobReconcileSource source : sources) {
            List<JobEnvelope> pending;
            try {
                pending = source.pending(limit);
            } catch (RuntimeException e) {
                // 单个域查询失败不能拖垮其它域：下一个 5 分钟还会再来
                log.warn("补扫查询失败，跳过本次 source={}", source.name(), e);
                continue;
            }
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            log.info("补扫发现待办任务 source={} 条数={}", source.name(), pending.size());
            for (JobEnvelope envelope : pending) {
                try {
                    publisher.publish(envelope);
                    published++;
                } catch (RuntimeException e) {
                    // 队列不可用/类型非法：整轮都发不出去，直接结束，等下一轮
                    log.warn("补扫投递失败，终止本轮 source={} jobId={}", source.name(), envelope.jobId(), e);
                    return published;
                }
            }
        }
        return published;
    }
}
