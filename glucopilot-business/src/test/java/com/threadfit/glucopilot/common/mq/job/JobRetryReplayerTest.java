package com.threadfit.glucopilot.common.mq.job;

import com.threadfit.glucopilot.common.mq.MqProperties;
import com.threadfit.glucopilot.common.mq.RabbitMqConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信重投：次数上限、坏消息丢弃、重投失败不炸。
 */
class JobRetryReplayerTest {

    private final JobProperties properties = new JobProperties();
    private final JobPublisher publisher = mock(JobPublisher.class);
    private final JobMetrics metrics = new JobMetrics();
    private final MessageConverter converter = new RabbitMqConfig(new MqProperties()).glucopilotMessageConverter();
    private final RabbitTemplate template = mock(RabbitTemplate.class);

    @Test
    void 未到次数上限应重新投递并递增尝试次数() {
        JobEnvelope failed = JobEnvelope.of(JobTypes.MEMORY_EXTRACT, 7L, null, 3L, "session-ended").withAttempt(0);
        when(template.receive(anyString(), anyLong())).thenReturn(message(failed), (Message) null);

        assertThat(replayer().replayOnce()).isEqualTo(1);

        verify(publisher).publish(failed.withAttempt(1));
        assertThat(metrics.snapshot().retried()).isEqualTo(1);
    }

    @Test
    void 达到上限后应丢弃死信并计数() {
        properties.setMaxAttempts(3);
        JobEnvelope exhausted = JobEnvelope.of(JobTypes.OCR_RECOGNIZE, 9L, null, 3L, "upload").withAttempt(3);
        when(template.receive(anyString(), anyLong())).thenReturn(message(exhausted), (Message) null);

        assertThat(replayer().replayOnce()).isZero();

        verify(publisher, never()).publish(any());
        assertThat(metrics.snapshot().dropped()).isEqualTo(1);
    }

    @Test
    void 无法解析的死信应丢弃并计数() {
        Message garbage = new Message("{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new MessageProperties());
        when(template.receive(anyString(), anyLong())).thenReturn(garbage, (Message) null);

        assertThat(replayer().replayOnce()).isZero();

        verify(publisher, never()).publish(any());
        assertThat(metrics.snapshot().dropped()).isEqualTo(1);
    }

    @Test
    void 重投失败不应中断后面队列的处理() {
        JobEnvelope failed = JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 1L, 1, null, "publish");
        when(template.receive(eq("glucopilot.job.moderation.dlq"), anyLong()))
                .thenReturn(message(failed), (Message) null);
        when(template.receive(eq("glucopilot.job.memory.dlq"), anyLong())).thenReturn(null);
        when(template.receive(eq("glucopilot.job.ocr.dlq"), anyLong())).thenReturn(null);
        doThrow(new IllegalStateException("queue disabled")).when(publisher).publish(any());

        assertThat(replayer().replayOnce()).isZero();

        assertThat(metrics.snapshot().dropped()).isEqualTo(1);
        // 三个队列都要扫到：第一个域失败不能让后面两个域永远不被重投
        verify(template).receive(eq("glucopilot.job.memory.dlq"), anyLong());
        verify(template).receive(eq("glucopilot.job.ocr.dlq"), anyLong());
    }

    private JobRetryReplayer replayer() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new JobRetryReplayer(properties, publisher, metrics, converter, provider);
    }

    private Message message(JobEnvelope envelope) {
        return converter.toMessage(envelope, new MessageProperties());
    }
}
