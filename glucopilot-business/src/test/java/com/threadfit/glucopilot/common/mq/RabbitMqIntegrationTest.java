package com.threadfit.glucopilot.common.mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对着**真实代理**的集成验证（默认不跑）。
 *
 * <p>启用方式（PowerShell）：
 * <pre>
 *   $env:MQ_INTEGRATION="true"; $env:MQ_HOST="192.168.100.3"; $env:MQ_PORT="1186"
 *   $env:MQ_USERNAME="p10_admin"; $env:MQ_PASSWORD="…"; $env:MQ_VHOST="glucopilot"
 *   mvn -pl glucopilot-business -am test -Dtest=RabbitMqIntegrationTest
 * </pre>
 *
 * <p>验证三件事，全部是单测证明不了、只有在真代理上才成立的：
 * ①拓扑能被声明并出现在 vhost 里；②发布 → 路由 → 队列 的往返；③<b>拒绝消息后真的进死信队列</b>
 * （这是「死信参数写对了」的唯一证据——参数写错时代理不会报错，只会悄悄丢弃）。
 *
 * <p>它刻意不复用 Spring 上下文：不启动整个应用、不连数据库，跑一次几秒钟。
 */
@EnabledIfEnvironmentVariable(named = "MQ_INTEGRATION", matches = "true")
class RabbitMqIntegrationTest {

    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate template;
    private MqProperties properties;
    private MqPublisher publisher;

    @BeforeEach
    void setUp() {
        connectionFactory = new CachingConnectionFactory(env("MQ_HOST", "127.0.0.1"), intEnv("MQ_PORT", 5672));
        connectionFactory.setUsername(env("MQ_USERNAME", "guest"));
        connectionFactory.setPassword(env("MQ_PASSWORD", "guest"));
        connectionFactory.setVirtualHost(env("MQ_VHOST", "/"));

        properties = new MqProperties();
        RabbitMqConfig config = new RabbitMqConfig(properties);

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.declareExchange(config.glucopilotEventExchange());
        admin.declareExchange(config.glucopilotDeadLetterExchange());
        admin.declareQueue(config.glucopilotEventQueue());
        admin.declareQueue(config.glucopilotDeadLetterQueue());
        admin.declareBinding(config.glucopilotEventBinding(config.glucopilotEventQueue(), config.glucopilotEventExchange()));
        admin.declareBinding(config.glucopilotDeadLetterBinding(config.glucopilotDeadLetterQueue(), config.glucopilotDeadLetterExchange()));

        template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(config.glucopilotMessageConverter());
        template.setMandatory(true);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("rabbitTemplate", template));
        publisher = new MqPublisher(properties, beanFactory.getBeanProvider(RabbitTemplate.class));

        // 上一轮残留会干扰断言：先清空两条队列
        template.execute((Channel channel) -> {
            channel.queuePurge(properties.getQueue());
            channel.queuePurge(properties.getDeadLetterQueue());
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("真实代理：发布后应能从通用队列取回同一条消息")
    void publishShouldReachQueue() {
        String messageId = publisher.publish("core.it.smoke", Map.of("hello", "rabbit"));

        Message received = template.receive(properties.getQueue(), 5000);

        assertThat(received).as("5 秒内应在队列里收到消息").isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(messageId);
        assertThat(received.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).contains("rabbit");
        assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("core.it.smoke");
    }

    @Test
    @DisplayName("真实代理：消息被拒绝后应进死信队列")
    void rejectedMessageShouldLandInDeadLetterQueue() {
        publisher.publish("core.it.dlq", Map.of("poison", true));

        // 手动取一条并 nack(requeue=false)：这等价于「监听器抛异常且不重回队列」
        Boolean rejected = template.execute((Channel channel) -> {
            GetResponse response = channel.basicGet(properties.getQueue(), false);
            if (response == null) {
                return false;
            }
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return true;
        });
        assertThat(rejected).as("通用队列里应有可被拒绝的消息").isTrue();

        Message dead = template.receive(properties.getDeadLetterQueue(), 5000);
        assertThat(dead).as("拒绝后的消息应被投递到死信队列").isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains("poison");
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
