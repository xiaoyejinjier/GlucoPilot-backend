package com.threadfit.glucopilot.common.mq;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件发送端口：业务只需要「发给谁 + 发什么」，不关心 exchange 名字与消息属性。
 *
 * <h2>为什么不直接用 {@link RabbitTemplate}</h2>
 * 三个理由：①强制统一带上 {@code messageId}/{@code timestamp}，否则线上排查时无法把
 * 生产日志与代理端日志对上；②交换机的名字只应该出现在配置里；③把「发送失败」收敛成
 * 项目统一的 {@link BizException}，调用方不需要认识 {@link AmqpException}。
 *
 * <h2>失败语义</h2>
 * <b>不吞异常</b>：代理不可达、连接被拒时抛出 {@link ErrorCode#SERVICE_UNAVAILABLE}，
 * 让调用方决定是回滚、重试还是返回 5xx。刻意不做「失败就写本地表稍后补发」——
 * 那需要一个 outbox 表与补偿任务，属于业务级可靠性设计，等真有强一致需求时再做。
 *
 * <h2>为什么 payload 要求是简单对象</h2>
 * 转换器会把类名写进 {@code __TypeId__} 头。跨服务消费时对方通常没有这个类，
 * 所以约定：<b>事件负载用 record/DTO 或 Map</b>，字段变更只增不改；
 * 消费方按 {@code Map} 或自己的 DTO 接收（多字段会被忽略，见转换器的
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}）。
 */
@Slf4j
@Component
public class MqPublisher {

    private final MqProperties properties;
    private final ObjectProvider<RabbitTemplate> templateProvider;

    public MqPublisher(MqProperties properties, ObjectProvider<RabbitTemplate> templateProvider) {
        this.properties = properties;
        this.templateProvider = templateProvider;
    }

    /**
     * 发送一条事件。
     *
     * @param routingKey 路由键，约定 {@code core.<业务>.<动作>}（通用队列订阅 {@code core.#}）
     * @param payload    事件负载，需可被 Jackson 序列化
     * @return 消息 id（同时写入 {@code messageId} 头，便于在代理后台按 id 检索）
     */
    public String publish(String routingKey, Object payload) {
        if (!properties.isEnabled()) {
            // 明确报错而不是静默成功：调用方以为事件已发出、实际什么也没发生，是最难查的一类 bug
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "消息队列已关闭（glucopilot.mq.enabled=false）");
        }
        RabbitTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "消息队列未就绪（RabbitTemplate 不可用）");
        }
        String messageId = UUID.randomUUID().toString();
        MessagePostProcessor processor = message -> {
            MessageProperties props = message.getMessageProperties();
            props.setMessageId(messageId);
            props.setTimestamp(java.util.Date.from(Instant.now()));
            props.setHeader("x-source", "glucopilot-backend");
            return message;
        };
        try {
            template.convertAndSend(properties.getExchange(), routingKey, payload, processor,
                    new CorrelationData(messageId));
        } catch (AmqpException e) {
            log.warn("消息发送失败 exchange={} routingKey={} messageId={}", properties.getExchange(),
                    routingKey, messageId, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "消息发送失败，请稍后重试");
        }
        log.info("消息已发送 exchange={} routingKey={} messageId={}", properties.getExchange(), routingKey,
                messageId);
        return messageId;
    }
}
