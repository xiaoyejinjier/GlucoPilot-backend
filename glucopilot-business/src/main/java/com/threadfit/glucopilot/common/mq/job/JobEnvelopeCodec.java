package com.threadfit.glucopilot.common.mq.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 任务信封的<b>解码器</b>：把 {@link Message} 还原成 {@link JobEnvelope}。
 *
 * <h2>为什么单独成一个类</h2>
 *
 * 消费端（{@link JobListener}）与重投端（{@link JobRetryReplayer}）都要做同一件事，且都踩过同一个坑：{@code
 * fromMessage} 有 {@code (Message)} / {@code (Message, Class)} / {@code (Message, ParameterizedTypeReference)}
 * 三个重载，其中 {@code Class} 版本会被忽略并回落到消息头里的类型信息——而我们的发送端刻意不带 {@code
 * __TypeId__}（不放宽反序列化白名单），回落就等于解码失败。所以必须显式用 {@link ParameterizedTypeReference}。
 *
 * <p>解码失败返回 {@code null} 而不是抛异常：调用方对"读不懂的消息"的处理是固定的（转死信、三次后丢弃）， 不需要每种调用方各写一遍异常逻辑。
 */
@Component
@RequiredArgsConstructor
public class JobEnvelopeCodec {

    private static final Logger LOG = LoggerFactory.getLogger(JobEnvelopeCodec.class);

    private final MessageConverter messageConverter;

    /** @return 解码结果；消息体不是信封时返回 null */
    public JobEnvelope decode(Message message) {
        try {
            if (messageConverter instanceof SmartMessageConverter smart) {
                Object payload = smart.fromMessage(message, new ParameterizedTypeReference<JobEnvelope>() {
                });
                return payload instanceof JobEnvelope envelope ? envelope : null;
            }
            Object payload = messageConverter.fromMessage(message);
            return payload instanceof JobEnvelope envelope ? envelope : null;
        } catch (RuntimeException e) {
            LOG.debug("任务消息反序列化失败", e);
            return null;
        }
    }
}
