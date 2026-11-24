package com.threadfit.glucopilot.common.mq;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqPublisher} 的失败语义与消息属性。
 *
 * <p>重点不是「能发出去」，而是三条容易做错的边界：①开关关掉时必须抛错而不是静默成功；
 * ②代理挂了要收敛成 503 而不是把 {@code AmqpException} 泄漏给业务；③每条消息都要带
 * {@code messageId} 与 {@code timestamp}，否则线上对不上生产端与代理端日志。
 */
class MqPublisherTest {

    private MqProperties properties;
    private RabbitTemplate template;
    private MqPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new MqProperties();
        template = mock(RabbitTemplate.class);
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        publisher = new MqPublisher(properties, provider);
    }

    @Test
    @DisplayName("发送成功时应带上 messageId 与时间戳并返回 id")
    void publishShouldAttachMessageId() {
        String messageId = publisher.publish("core.audit.created", java.util.Map.of("k", "v"));

        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(template).convertAndSend(eq("glucopilot.event"), eq("core.audit.created"), any(),
                processor.capture(), correlation.capture());

        Message message = new Message(new byte[0], new org.springframework.amqp.core.MessageProperties());
        Message processed = processor.getValue().postProcessMessage(message);
        assertThat(processed).isSameAs(message);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(messageId);
        assertThat(message.getMessageProperties().getTimestamp()).isNotNull();
        Object source = message.getMessageProperties().getHeader("x-source");
        assertThat(source).isEqualTo("glucopilot-backend");
        assertThat(correlation.getValue().getId()).isEqualTo(messageId);
    }

    @Test
    @DisplayName("开关关闭时应抛 503，且不触碰模板")
    void publishShouldFailWhenDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> publisher.publish("core.x", "payload"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE)
                .hasMessageContaining("已关闭");
        verify(template, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("代理不可达时应收敛成 503 而不是泄漏 AmqpException")
    void publishShouldWrapAmqpException() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(template).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish("core.x", "payload"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE)
                .hasMessageContaining("发送失败");
    }

    @Test
    @DisplayName("模板不可用（MQ 未启用 Bean）时应抛 503")
    void publishShouldFailWhenTemplateMissing() {
        ObjectProvider<RabbitTemplate> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        MqPublisher degraded = new MqPublisher(properties, empty);

        assertThatThrownBy(() -> degraded.publish("core.x", "payload"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE)
                .hasMessageContaining("未就绪");
    }
}
