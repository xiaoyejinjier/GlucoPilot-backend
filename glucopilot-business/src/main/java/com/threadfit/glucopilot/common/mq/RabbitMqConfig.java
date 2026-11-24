package com.threadfit.glucopilot.common.mq;

import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ 基础配置：拓扑声明 + JSON 消息转换 + 发送端行为。
 *
 * <h2>只做四件事，别的一律交给 Spring Boot</h2>
 * <ol>
 *   <li>声明拓扑（1 个业务交换机 + 1 条通用队列 + 死信交换机/队列 + 两条绑定）；</li>
 *   <li>指定 JSON 序列化（{@link JacksonJsonMessageConverter}），避免默认的
 *       {@code SimpleMessageConverter} 把 Java 对象序列化成不可读的二进制；</li>
 *   <li>把发送端改成「不可路由就返回」+「确认/退回都打日志」，否则消息静默消失；</li>
 *   <li>声明失败不阻断启动（{@code missing-queues-fatal=false} 写在 yaml 里）。</li>
 * </ol>
 * 连接、确认模式、监听容器并发度等「怎么连 / 怎么收」的参数全部走 {@code spring.rabbitmq.*}
 * （见 application.yaml），这里不重复一份——两处配置是运维事故的常见来源。
 *
 * <h2>为什么不用 RabbitAdmin 显式声明</h2>
 * Spring Boot 的 {@code RabbitAutoConfiguration} 在 {@code spring.rabbitmq.dynamic=true}（默认）时
 * 已经注册了 {@code AmqpAdmin}，它会在首次建立连接后把容器里所有 {@link Queue}/{@code Exchange}/
 * {@link Binding} Bean 声明到代理上。自己再 new 一个只会多出一个重复的声明者。
 *
 * <h2>为什么 {@code enabled=false} 时连 Bean 都不注册</h2>
 * 本地写单测、或临时没有代理的环境里，只要不注册这些 Bean，{@code MqPublisher} 的
 * {@code ObjectProvider} 就拿不到实例，调用点会得到明确的「MQ 已关闭」异常，
 * 而不是"配置看着是开的、消息却发不出去"。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "glucopilot.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    private final MqProperties properties;

    /** 业务事件交换机：topic + 持久化 + 不自动删除（自动删除的交换机在最后一个绑定消失时会没）。 */
    @Bean
    public TopicExchange glucopilotEventExchange() {
        return new TopicExchange(properties.getExchange(), true, false);
    }

    /** 死信交换机：与业务交换机同规格，只是名字带 {@code .dlx}。 */
    @Bean
    public TopicExchange glucopilotDeadLetterExchange() {
        return new TopicExchange(properties.getDeadLetterExchange(), true, false);
    }

    /**
     * 通用事件队列。
     *
     * <p>三个参数都是有原因的：
     * {@code x-dead-letter-exchange} 让「监听器抛异常且不重回队列」的消息进死信而不是被丢弃；
     * {@code x-dead-letter-routing-key} 固定成死信队列的键，避免原始 routingKey 在死信交换机上无匹配；
     * 不设 {@code x-message-ttl}/长度上限——事件量级远没到需要主动丢弃的程度，先别给自己挖坑。
     */
    @Bean
    public Queue glucopilotEventQueue() {
        return QueueBuilder.durable(properties.getQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getDeadLetterQueue())
                .build();
    }

    /** 死信队列：不设 TTL，保留到人工处理（队列名里带 {@code .dlq} 便于在后台一眼认出）。 */
    @Bean
    public Queue glucopilotDeadLetterQueue() {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    /** 业务队列绑定：{@code core.#} 只收 {@code core.} 开头的事件。 */
    @Bean
    public Binding glucopilotEventBinding(Queue glucopilotEventQueue, TopicExchange glucopilotEventExchange) {
        return BindingBuilder.bind(glucopilotEventQueue)
                .to(glucopilotEventExchange)
                .with(properties.getQueueRoutingKey());
    }

    /** 死信绑定：{@code #} 全收，任何死信都不因为路由键不匹配而丢失。 */
    @Bean
    public Binding glucopilotDeadLetterBinding(Queue glucopilotDeadLetterQueue,
                                               TopicExchange glucopilotDeadLetterExchange) {
        return BindingBuilder.bind(glucopilotDeadLetterQueue)
                .to(glucopilotDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    /**
     * JSON 消息转换器。
     *
     * <p>刻意**新建**一个 {@link JsonMapper} 而不是注入 Web 层那个：Web 的 mapper 装了
     * 「Long 序列化成字符串」的定制（防前端精度丢失），对消息负载来说这是污染——
     * 消费方拿到的是 {@code "123"} 而不是 {@code 123}，一旦有跨语言的消费者就会踩坑。
     *
     * <p>开启 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}：消费者与服务端常常不是同一版本，
     * 多出的字段应该被忽略而不是让反序列化失败。
     *
     * <p><b>刻意不放开 {@code __TypeId__} 白名单</b>：类型头里带的是发送端的类名，反序列化时按它
     * 反射实例化，等价于「谁能发消息谁就能让消费者加载任意类」。而 Spring AMQP 的
     * {@code setTrustedPackages} 是**整包精确匹配**（源码里就是 {@code packageName.equals(trustedPackage)}），
     * 写 {@code com.threadfit.glucopilot} 并不会覆盖子包，要放开就得逐个包枚举——既脆又危险。
     * 因此约定：<b>消费方声明目标类型</b>。注意 {@code fromMessage(message, hint)} 的 hint
     * 只认 {@link org.springframework.core.ParameterizedTypeReference}（传 {@code Foo.class}
     * 会被忽略、回落到类型头并触发白名单校验）；{@code @RabbitListener} 方法的参数类型
     * 正好以那种形式传下来，所以监听器侧无需额外处理。也可以按 {@code Map} 接收。
     * 类型头照常写进消息（排查时有用），但不依赖它做实例化。
     */
    @Bean
    public MessageConverter glucopilotMessageConverter() {
        JsonMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return new JacksonJsonMessageConverter(mapper);
    }

    /**
     * 发送端定制：强制 mandatory + 确认/退回都留日志。
     *
     * <p>{@code setMandatory(true)} 让「交换机上没有任何队列匹配」的消息触发 return 回调，
     * 而不是被代理静默丢弃；配合 {@code spring.rabbitmq.publisher-returns=true} 才能拿到回调。
     * 两个回调都只 WARN，不抛异常：确认是异步的，抛也抛不回给调用方，留痕交给日志与死信队列。
     */
    @Bean
    public RabbitTemplateCustomizer glucopilotRabbitTemplateCustomizer() {
        return template -> {
            template.setMandatory(true);
            template.setConfirmCallback((correlationData, ack, cause) -> {
                if (!ack) {
                    log.warn("消息未被代理确认 correlation={} cause={}",
                            correlationData == null ? null : correlationData.getId(), cause);
                }
            });
            template.setReturnsCallback(returned -> log.warn(
                    "消息无法路由已退回 exchange={} routingKey={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        };
    }
}
