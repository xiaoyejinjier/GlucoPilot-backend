package com.threadfit.glucopilot.common.mq.job;

import com.threadfit.glucopilot.common.mq.MqProperties;
import com.threadfit.glucopilot.common.mq.RabbitMqConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务信封的标识规则与 JSON 往返。
 */
class JobEnvelopeTest {

    @Test
    void jobId应带上版本号以便区分同一对象的两次任务() {
        assertThat(JobEnvelope.jobIdOf(JobTypes.MODERATION_ARTICLE, 123L, 2))
                .isEqualTo("MODERATION_ARTICLE:123:r2");
        assertThat(JobEnvelope.jobIdOf(JobTypes.MEMORY_EXTRACT, 9L, null))
                .isEqualTo("MEMORY_EXTRACT:9");
    }

    @Test
    void 新建信封的尝试次数为零且记录创建时间() {
        JobEnvelope envelope = JobEnvelope.of(JobTypes.OCR_RECOGNIZE, 77L, null, 5L, "manual");
        assertThat(envelope.attempt()).isZero();
        assertThat(envelope.jobId()).isEqualTo("OCR_RECOGNIZE:77");
        assertThat(envelope.createdAt()).isNotBlank();
        assertThat(envelope.userId()).isEqualTo(5L);
    }

    @Test
    void 重投递增尝试次数但不改变任务标识() {
        JobEnvelope first = JobEnvelope.of(JobTypes.MODERATION_COMMENT, 42L, 1, null, null);
        JobEnvelope retried = first.withAttempt(2);
        assertThat(retried.attempt()).isEqualTo(2);
        // jobId 不变：否则死信与业务日志就用同一个串对不上了
        assertThat(retried.jobId()).isEqualTo(first.jobId());
        assertThat(retried.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void 路由键由类型推导且未知类型直接失败() {
        assertThat(JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 1L, null, null, null).routingKey())
                .isEqualTo("job.moderation.article");
        assertThat(JobEnvelope.of(JobTypes.MEMORY_EXTRACT, 1L, null, null, null).routingKey())
                .isEqualTo("job.memory.extract");
        JobEnvelope unknown = new JobEnvelope("x:1", "NOT_A_TYPE", 1L, null, null, null, 0, "now");
        assertThatThrownBy(unknown::routingKey).isInstanceOf(IllegalArgumentException.class);
        assertThat(JobTypes.known("NOT_A_TYPE")).isFalse();
    }

    @Test
    void 信封应能经消息转换器往返且字段不丢() {
        MessageConverter converter = new RabbitMqConfig(new MqProperties()).glucopilotMessageConverter();
        JobEnvelope original = new JobEnvelope("MODERATION_COMMENT:88:r3", JobTypes.MODERATION_COMMENT,
                88L, 3, 5L, "comment-created", 2, "2026-11-24T10:00:00Z");

        Message message = converter.toMessage(original, new MessageProperties());
        Object parsed = ((SmartMessageConverter) converter)
                .fromMessage(message, new ParameterizedTypeReference<JobEnvelope>() {
                });

        assertThat(parsed).isEqualTo(original);
    }
}
