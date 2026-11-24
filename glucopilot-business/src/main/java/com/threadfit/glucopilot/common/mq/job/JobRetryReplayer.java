package com.threadfit.glucopilot.common.mq.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 死信重投器：把三个死信队列里的任务按「有限次数 + 固定节奏」重新投回业务交换机。
 *
 * <h2>为什么重投要做成"从死信取、重新发"而不是"把消息放回原队列"</h2>
 * 原队列里排在后面的任务是无辜的：一条处理不了的消息如果 {@code requeue=true} 会回到队头，
 * 立刻又被投给同一个消费者，形成堵点（队头阻塞）。从死信取出来重新走一次交换机，
 * 等价于"排到队尾重新试"，而且中间那段时间（本类每 60 秒扫一次）就是天然的重试间隔，
 * 不需要额外实现延迟队列。
 *
 * <h2>为什么次数用尽后丢弃、而不是留在死信里</h2>
 * 留在死信里需要"看一眼再放回去"，而 RabbitMQ 的 {@code basicGet} 取走即出队，
 * 放回去等于又发了一条新消息、{@code attempt} 还会继续涨——要么无限打转，
 * 要么得再建一个 parked 队列，两个都不值得。这里改成：**打一条带完整信封的 WARN 后丢弃**。
 * 之所以敢丢，是因为任务真相在数据库里（{@code ai_status}/{@code task_status}），
 * 这条消息只是"通知"；人工处置时看的是业务记录 + 日志里的 jobId，而不是 MQ 里那条副本。
 *
 * <h2>为什么不解析 {@code x-death} 的时间</h2>
 * 它记的是"进入死信的时刻"，精度足够但格式随 broker 版本变化，且我们真正需要的是
 * "两条重投之间的间隔是否够久"——用轮询间隔（默认 60 秒）表达这件事更简单、更可控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobRetryReplayer {

    private static final int RECEIVE_TIMEOUT_MS = 200;

    private final JobProperties properties;
    private final JobPublisher publisher;
    private final JobMetrics metrics;
    private final MessageConverter messageConverter;
    private final ObjectProvider<RabbitTemplate> templateProvider;

    /** 定时重投（默认 60 秒一次，同时也充当重试间隔）。 */
    @Scheduled(fixedDelayString = "${glucopilot.mq.jobs.replay-interval-ms:60000}")
    public void scheduledReplay() {
        if (!properties.isEnabled()) {
            return;
        }
        int replayed = replayOnce();
        if (replayed > 0) {
            log.info("死信重投完成：{} 条，计数={}", replayed, metrics.snapshot());
        }
    }

    /**
     * 扫三个死信队列并重投（包级可见，供单测直接调用）。
     *
     * @return 成功重投的条数
     */
    int replayOnce() {
        RabbitTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            return 0;
        }
        int replayed = 0;
        for (String queue : List.of(properties.getModerationQueue(), properties.getMemoryQueue(), properties.getOcrQueue())) {
            replayed += drain(template, queue);
        }
        return replayed;
    }

    private int drain(RabbitTemplate template, String queue) {
        String dlq = JobProperties.dlqOf(queue);
        int replayed = 0;
        while (true) {
            Message message;
            try {
                message = template.receive(dlq, RECEIVE_TIMEOUT_MS);
            } catch (AmqpException e) {
                log.warn("读取死信失败，本轮跳过 queue={}", dlq, e);
                return replayed;
            }
            if (message == null) {
                return replayed;
            }
            JobEnvelope envelope = parse(message);
            if (envelope == null) {
                metrics.countDropped();
                log.warn("死信中有无法解析的消息，已丢弃 queue={} body={}", dlq, bodyText(message));
                continue;
            }
            int nextAttempt = envelope.attempt() + 1;
            if (nextAttempt > properties.getMaxAttempts()) {
                metrics.countDropped();
                log.warn("任务重试次数已用尽（上限 {} 次），丢弃死信；业务记录仍是失败态，需人工处置 queue={} jobId={} type={} refId={}",
                        properties.getMaxAttempts(), dlq, envelope.jobId(), envelope.type(), envelope.refId());
                continue;
            }
            try {
                publisher.publish(envelope.withAttempt(nextAttempt));
                metrics.countRetried();
                replayed++;
                log.info("死信重投 jobId={} 第 {} 次", envelope.jobId(), nextAttempt);
            } catch (RuntimeException e) {
                // 已经出队且发不出去：只能记日志（body 里就是完整信封，可人工重放）
                metrics.countDropped();
                log.warn("死信重投失败，消息已出队且未能重新入队 queue={} jobId={} body={}",
                        dlq, envelope.jobId(), bodyText(message), e);
            }
        }
    }

    private JobEnvelope parse(Message message) {
        try {
            if (messageConverter instanceof SmartMessageConverter smart) {
                // hint 必须是 ParameterizedTypeReference：传 Class 会被忽略并回落到类型头，
                // 而我们的发送端不带 __TypeId__（不放宽反序列化白名单）。
                // 先接成 Object 再判型：fromMessage 有 Object / ParameterizedTypeReference 两个重载，
                // 直接 return 会被解析成 Object 版本（编译期看不出，报"Object 无法转换为 JobEnvelope"）。
                Object payload = smart.fromMessage(message, new ParameterizedTypeReference<JobEnvelope>() {
                });
                return payload instanceof JobEnvelope envelope ? envelope : null;
            }
            Object payload = messageConverter.fromMessage(message);
            return payload instanceof JobEnvelope envelope ? envelope : null;
        } catch (RuntimeException e) {
            log.debug("死信消息反序列化失败", e);
            return null;
        }
    }

    private String bodyText(Message message) {
        byte[] body = message.getBody();
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
