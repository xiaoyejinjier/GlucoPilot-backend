package com.threadfit.glucopilot.common.mq.job;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mq.MqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 任务投递口：把 {@link JobEnvelope} 发到任务交换机。
 *
 * <h2>为什么要在事务提交后才发</h2>
 * 典型场景：审核入库（写 {@code AI_STATUS=PENDING}）与投递是同一段业务代码。
 * 如果在事务内发消息，消息会先到 broker、消费端立刻回库却读不到这条记录（还是旧状态），
 * 于是白白消耗一次重试；更糟的是事务回滚了但消息已经发出，消费端永远等不到数据。
 * 所以只要有同步活动，就注册 {@code afterCommit} 再发。
 *
 * <h2>afterCommit 里失败为什么不往外抛</h2>
 * `afterCommit` 抛异常时事务已经提交完成，异常只会让调用方看到"失败"却什么都没回滚。
 * 这种情况下**补扫是唯一可靠的兜底**（{@code JobReconciler} 每 5 分钟扫一次 PENDING 且未认领的任务），
 * 所以这里记 WARN + 计数，把决定权交给补扫，而不是给调用方一个误导性的失败。
 *
 * <h2>没有事务时为什么失败要抛</h2>
 * 没有事务就没有"库已提交"这一层保护，静默失败会真的丢任务；此时抛
 * {@link ErrorCode#SERVICE_UNAVAILABLE} 让调用方决定是重试还是走同步路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobPublisher {

    private final MqProperties mqProperties;
    private final JobProperties jobProperties;
    private final ObjectProvider<RabbitTemplate> templateProvider;
    private final JobMetrics metrics;

    /**
     * 投递一条任务。
     *
     * @throws IllegalArgumentException 类型未知（拼错路由键比丢消息更容易查）
     * @throws BizException             队列未启用或发送失败（仅在没有事务时）
     */
    public String publish(JobEnvelope envelope) {
        Objects.requireNonNull(envelope, "任务信封不能为空");
        String routingKey = envelope.routingKey();
        if (!jobProperties.isEnabled() || !mqProperties.isEnabled()) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "任务队列未启用，暂不能派发后台任务");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (!trySend(envelope, routingKey)) {
                        metrics.countDropped();
                        log.warn("事务提交后投递任务失败，等补扫重发 jobId={} type={}", envelope.jobId(), envelope.type());
                    }
                }
            });
            log.debug("任务将在事务提交后投递 jobId={} routingKey={}", envelope.jobId(), routingKey);
            return envelope.jobId();
        }
        if (!trySend(envelope, routingKey)) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "后台任务派发失败，请稍后重试");
        }
        return envelope.jobId();
    }

    private boolean trySend(JobEnvelope envelope, String routingKey) {
        RabbitTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("没有可用的 RabbitTemplate，任务未投递 jobId={}", envelope.jobId());
            return false;
        }
        try {
            template.convertAndSend(jobProperties.getExchange(), routingKey, envelope, message -> {
                // 消息头 messageId 与管理台「消息详情」直接对应，方便用同一个 jobId 串起日志与死信
                message.getMessageProperties().setMessageId(envelope.jobId());
                return message;
            }, new CorrelationData(envelope.jobId()));
            metrics.countPublished();
            log.info("任务已投递 jobId={} type={} routingKey={}", envelope.jobId(), envelope.type(), routingKey);
            return true;
        } catch (AmqpException e) {
            log.warn("任务投递异常 jobId={} routingKey={}", envelope.jobId(), routingKey, e);
            return false;
        }
    }
}
