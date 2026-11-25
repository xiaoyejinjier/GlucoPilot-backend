package com.threadfit.glucopilot.common.mq.job;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mq.MqProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务投递口：消息头、事务后发送、失败语义。
 */
class JobPublisherTest {

    private static final JobEnvelope ENVELOPE =
            JobEnvelope.of(JobTypes.MODERATION_ARTICLE, 11L, 1, 2L, "publish");

    private final MqProperties mqProperties = new MqProperties();
    private final JobProperties jobProperties = new JobProperties();
    private final JobMetrics metrics = new JobMetrics();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 投递应使用任务交换机与类型推导的路由键并写消息头() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        JobPublisher publisher = publisher(template);

        assertThat(publisher.publish(ENVELOPE)).isEqualTo(ENVELOPE.jobId());

        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(template).convertAndSend(eq("glucopilot.job"), eq("job.moderation.article"), eq(ENVELOPE),
                processor.capture(), correlation.capture());
        MessageProperties properties = new MessageProperties();
        processor.getValue().postProcessMessage(new org.springframework.amqp.core.Message(new byte[0], properties));
        // 消息头与信封里的 jobId 一致：管理台「消息详情」能直接搜到同一条业务日志
        assertThat(properties.getMessageId()).isEqualTo(ENVELOPE.jobId());
        assertThat(correlation.getValue().getId()).isEqualTo(ENVELOPE.jobId());
        assertThat(metrics.snapshot().published()).isEqualTo(1);
    }

    @Test
    void 任务队列关闭时投递应报503() {
        jobProperties.setEnabled(false);
        JobPublisher publisher = publisher(mock(RabbitTemplate.class));

        assertThatThrownBy(() -> publisher.publish(ENVELOPE))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void 没有可用模板时应报503而不是静默丢() {
        JobPublisher publisher = publisher(null);

        assertThatThrownBy(() -> publisher.publish(ENVELOPE))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void 发送异常应收敛成503() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new ConnectException("refused")))
                .when(template).convertAndSend(any(String.class), any(String.class), any(Object.class),
                        any(MessagePostProcessor.class), any(CorrelationData.class));
        JobPublisher publisher = publisher(template);

        assertThatThrownBy(() -> publisher.publish(ENVELOPE))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(metrics.snapshot().published()).isZero();
    }

    @Test
    void 事务内应在提交后才发送() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        JobPublisher publisher = publisher(template);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(ENVELOPE);

        // 事务还没提交：消息不能先到 broker，否则消费端会读到未提交的旧状态
        verify(template, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(template).convertAndSend(eq("glucopilot.job"), eq("job.moderation.article"), eq(ENVELOPE),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void 提交后发送失败不应把异常抛给调用方() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new ConnectException("refused")))
                .when(template).convertAndSend(any(String.class), any(String.class), any(Object.class),
                        any(MessagePostProcessor.class), any(CorrelationData.class));
        JobPublisher publisher = publisher(template);
        TransactionSynchronizationManager.initSynchronization();

        assertThatCode(() -> publisher.publish(ENVELOPE)).doesNotThrowAnyException();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            // 事务已经提交，这里再抛只会让调用方误以为业务失败；兜底交给补扫
            assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        }
        assertThat(metrics.snapshot().dropped()).isEqualTo(1);
    }

    private JobPublisher publisher(RabbitTemplate template) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new JobPublisher(mqProperties, jobProperties, provider, metrics);
    }
}
