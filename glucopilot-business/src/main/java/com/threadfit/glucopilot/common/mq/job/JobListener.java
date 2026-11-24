package com.threadfit.glucopilot.common.mq.job;

import com.rabbitmq.client.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.io.IOException;

/**
 * 任务消息的<b>消费壳</b>：解码 → 分派 → 按三态决定 ack / nack。
 *
 * <h2>为什么 ack 必须手写</h2>
 *
 * 自动 ack 在"方法返回"时就确认了，而我们的处理器可能在里面调模型（秒级）——auto ack 期间进程崩溃，这条任务就永远丢了。 所以 {@link
 * JobContainerFactory 容器} 配 manual ack，由本类在拿到结论之后才确认。
 *
 * <h2>三态与 ack 的对应</h2>
 *
 * <ul>
 *   <li>{@link JobOutcome#SUCCESS} / {@link JobOutcome#PERMANENT} → ack。永久失败也不该在 DLQ 里打转：
 *       业务记录已经是终态（审核判错、对象已删），重投只会原地打转。
 *   <li>{@link JobOutcome#RETRYABLE} → {@code basicNack(requeue=false)}，由死信交换机转进对应队列的
 *       {@code .dlq}，再由 {@link JobRetryReplayer} 延迟重投。
 * </ul>
 *
 * <p><b>刻意不用 {@code requeue=true}</b>：立即重新入队会把它放回队头，紧接着被同一个消费者取到、再失败一次， 同队列后面的正常任务全被堵住（队头阻塞）。
 */
@Component
@RequiredArgsConstructor
public class JobListener {

    private static final Logger LOG = LoggerFactory.getLogger(JobListener.class);

    private final JobDispatcher dispatcher;

    private final JobEnvelopeCodec codec;

    private final JobMetrics metrics;

    /**
     * 处理一条任务消息并确认。
     *
     * <p>本方法不向上抛业务异常：{@code @RabbitListener} 方法抛异常时容器的行为取决于 {@code
     * defaultRequeueRejected}，而我们希望"失败一律进死信"这条规则只由三态决定，不要被容器默认值左右。
     */
    public void handle(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        JobEnvelope envelope = codec.decode(message);
        if (envelope == null) {
            metrics.countDropped();
            LOG.warn(
                    "任务消息无法解析，转死信（超过重试上限后丢弃）redelivered={}",
                    message.getMessageProperties().isRedelivered());
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        JobOutcome outcome;
        try {
            outcome = dispatcher.dispatch(envelope);
        } catch (RuntimeException e) {
            // 分派器本身已按处理器语义给出三态，能走到这里的只有"没预料到的"异常：按可重试处理
            LOG.warn("任务处理异常，转死信等待重投 type={} jobId={}", envelope.type(), envelope.jobId(), e);
            outcome = JobOutcome.RETRYABLE;
        }
        metrics.countHandled();
        if (outcome == JobOutcome.RETRYABLE) {
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        channel.basicAck(deliveryTag, false);
    }
}
