package com.threadfit.glucopilot.common.mq.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务分流：按类型找到处理器，走「认领 → 处理」两段，并把异常收敛成三态。
 *
 * <h2>为什么这里要接住所有异常</h2>
 * 没有这层接住，一个 NPE 会让消息在 broker 里反复重投（`requeue=true`）或者直接丢失
 * （`requeue=false` 且不进死信）。接住之后行为是确定的：**未预期异常一律
 * {@link JobOutcome#RETRYABLE}**——环境问题（DB 抖动、上游 5xx）比内容问题常见得多，
 * 交给重投器按 60 秒节奏试 3 次，比一次性丢掉安全。
 *
 * <h2>为什么没找到处理器也算 {@link JobOutcome#PERMANENT}</h2>
 * 说明是版本错配（老实例收到了新类型的消息）。这种消息本实例永远不会处理，
 * 重投只会制造噪音；留在死信队列里、由人在升级后重放，是唯一有意义的下场。
 */
@Slf4j
@Component
public class JobDispatcher {

    private final Map<String, JobHandler> handlers;

    public JobDispatcher(List<JobHandler> handlers) {
        // 用 putIfAbsent 而不是再包一层校验：重复注册是开发期错误，记一条 WARN 让它在测试里吵，
        // 但要保证容器能起来（宁可有一类任务被一个处理器接管，也不要整个应用起不来）
        Map<String, JobHandler> byType = new HashMap<>();
        for (JobHandler handler : handlers) {
            JobHandler previous = byType.putIfAbsent(handler.type(), handler);
            if (previous != null) {
                log.warn("任务类型 {} 被重复注册：{} 与 {}，以后者被忽略", handler.type(),
                        previous.getClass().getSimpleName(), handler.getClass().getSimpleName());
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    /**
     * 处理一条任务。
     *
     * @param envelope 已解析的信封；null 视为坏消息
     * @return 三态，调用方据此 ack 或投递到死信
     */
    public JobOutcome dispatch(JobEnvelope envelope) {
        if (envelope == null || !JobTypes.known(envelope.type())) {
            log.warn("无法识别的任务消息，直接确认丢弃：{}", envelope);
            return JobOutcome.PERMANENT;
        }
        JobHandler handler = handlers.get(envelope.type());
        if (handler == null) {
            log.warn("本实例没有 {} 的处理器（版本错配？），消息转入死信待人工重放 jobId={}",
                    envelope.type(), envelope.jobId());
            return JobOutcome.PERMANENT;
        }
        boolean claimed;
        try {
            claimed = handler.claim(envelope);
        } catch (RuntimeException e) {
            // 认领是每次处理的第一步，它的失败通常意味着 DB 不可用/条件列不存在 —— 环境问题
            log.warn("认领任务失败，稍后重投 jobId={} type={}", envelope.jobId(), envelope.type(), e);
            return JobOutcome.RETRYABLE;
        }
        if (!claimed) {
            // 正常路径：补扫与 MQ 投递重叠、或另一个实例先拿到了
            log.info("任务已被处理或状态不允许，跳过 jobId={}", envelope.jobId());
            return JobOutcome.SUCCESS;
        }
        try {
            JobOutcome outcome = handler.handle(envelope);
            return outcome == null ? JobOutcome.SUCCESS : outcome;
        } catch (RuntimeException e) {
            log.warn("处理任务失败，稍后重投 jobId={} type={}", envelope.jobId(), envelope.type(), e);
            return JobOutcome.RETRYABLE;
        }
    }

    /** 供启动日志/健康检查用：本实例注册了哪些类型。 */
    public java.util.Set<String> registeredTypes() {
        return handlers.keySet();
    }
}
