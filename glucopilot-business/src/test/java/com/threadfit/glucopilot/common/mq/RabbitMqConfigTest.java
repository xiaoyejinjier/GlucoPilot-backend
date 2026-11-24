package com.threadfit.glucopilot.common.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拓扑与序列化的单测：不需要代理，验证「声明的参数」与「JSON 往返」。
 *
 * <p>这些断言看似琐碎，但每一条都对应一次真实事故的可能：死信参数漏配 → 失败消息被丢弃；
 * 交换机非持久化 → 代理重启后拓扑消失、生产端 404；转换器选错 → 消息变成不可读二进制。
 */
class RabbitMqConfigTest {

    private MqProperties properties;
    private RabbitMqConfig config;

    @BeforeEach
    void setUp() {
        properties = new MqProperties();
        config = new RabbitMqConfig(properties);
    }

    @Test
    @DisplayName("业务交换机与死信交换机都应持久化且不自动删除")
    void exchangesShouldBeDurable() {
        TopicExchange event = config.glucopilotEventExchange();
        TopicExchange dlx = config.glucopilotDeadLetterExchange();

        assertThat(event.getName()).isEqualTo("glucopilot.event");
        assertThat(dlx.getName()).isEqualTo("glucopilot.event.dlx");
        assertThat(event.isDurable()).isTrue();
        assertThat(dlx.isDurable()).isTrue();
        assertThat(event.isAutoDelete()).isFalse();
        assertThat(dlx.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("通用队列必须带上死信交换机与固定的死信路由键")
    void eventQueueShouldDeclareDeadLetterArguments() {
        Queue queue = config.glucopilotEventQueue();

        assertThat(queue.getName()).isEqualTo("glucopilot.event.core");
        assertThat(queue.isDurable()).isTrue();
        Map<String, Object> args = queue.getArguments();
        assertThat(args)
                .containsEntry("x-dead-letter-exchange", "glucopilot.event.dlx")
                .containsEntry("x-dead-letter-routing-key", "glucopilot.event.core.dlq");
        // 不要给自己挖坑：没有 TTL 与长度上限，事件不该在无人处理时被代理悄悄丢掉
        assertThat(args).doesNotContainKeys("x-message-ttl", "x-max-length");
    }

    @Test
    @DisplayName("绑定关系：通用队列收 core.#，死信队列收 #")
    void bindingsShouldUseExpectedRoutingKeys() {
        Queue eventQueue = config.glucopilotEventQueue();
        TopicExchange eventExchange = config.glucopilotEventExchange();
        Queue dlq = config.glucopilotDeadLetterQueue();
        TopicExchange dlx = config.glucopilotDeadLetterExchange();

        Binding eventBinding = config.glucopilotEventBinding(eventQueue, eventExchange);
        Binding dlBinding = config.glucopilotDeadLetterBinding(dlq, dlx);

        assertThat(eventBinding.getDestination()).isEqualTo("glucopilot.event.core");
        assertThat(eventBinding.getExchange()).isEqualTo("glucopilot.event");
        assertThat(eventBinding.getRoutingKey()).isEqualTo("core.#");
        assertThat(dlBinding.getDestination()).isEqualTo("glucopilot.event.core.dlq");
        assertThat(dlBinding.getExchange()).isEqualTo("glucopilot.event.dlx");
        assertThat(dlBinding.getRoutingKey()).isEqualTo("#");
    }

    @Test
    @DisplayName("JSON 转换器应能往返自定义负载，且不把 Long 变成字符串")
    void converterShouldRoundTripWithoutLongAsString() {
        SmartMessageConverter converter = (SmartMessageConverter) config.glucopilotMessageConverter();
        assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);

        SampleEvent payload = new SampleEvent(42L, "测试内容", 95);
        Message message = converter.toMessage(payload, new MessageProperties());

        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        // Web 层的 Long→String 定制不能被带进消息负载：这里必须是裸数字
        assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"userId\":42").doesNotContain("\"userId\":\"42\"");

        Object back = converter.fromMessage(message, new ParameterizedTypeReference<SampleEvent>() { });
        assertThat(back).isInstanceOf(SampleEvent.class);
        SampleEvent parsed = (SampleEvent) back;
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.text()).isEqualTo("测试内容");
        assertThat(parsed.confidence()).isEqualTo(95);
    }

    @Test
    @DisplayName("负载新增字段时应能反序列化（向后兼容）")
    void converterShouldIgnoreUnknownProperties() {
        SmartMessageConverter converter = (SmartMessageConverter) config.glucopilotMessageConverter();
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(
                "{\"userId\":7,\"text\":\"ok\",\"confidence\":60,\"futureField\":\"ignored\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), props);

        // 消费方声明目标类型（不依赖 __TypeId__ 白名单，见 converter 的 javadoc）
        Object parsed = converter.fromMessage(message, new ParameterizedTypeReference<SampleEvent>() { });

        assertThat(parsed).isInstanceOf(SampleEvent.class);
        assertThat(((SampleEvent) parsed).userId()).isEqualTo(7L);
    }

    /** 测试用负载：record 才能被 Jackson 3 正确识别为不可变类型。 */
    record SampleEvent(long userId, String text, int confidence) {
    }
}
