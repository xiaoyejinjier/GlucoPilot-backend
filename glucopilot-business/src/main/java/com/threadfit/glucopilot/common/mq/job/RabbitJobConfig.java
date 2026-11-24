package com.threadfit.glucopilot.common.mq.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 任务队列的拓扑与消费者工厂。
 *
 * <h2>为什么不复用 {@code glucopilot.event}</h2>
 * 那套拓扑的语义是「领域事件」，死信队列绑的是 {@code #}（全收）。
 * 任务队列要有按域分桶的死信（一个域炸了不影响别的域），塞进同一组交换机会让
 * 两类消息的死信混在一个队列里，排查时无法区分"事件死信"与"任务死信"。
 *
 * <h2>为什么死信队列名是「业务队列 + .dlq」</h2>
 * 约定优于配置：给一个业务队列名就能推出它的死信队列，运维写命令、写监控时不用查文档；
 * 未来加第四个队列时也只改一处。
 *
 * <h2>为什么消费者工厂要单独一个（manual ack）</h2>
 * 任务必须"办完才 ack"：自动 ack 会在处理抛异常时也确认消息，等于丢任务。
 * 这里显式开 manual，由 {@code JobListener} 依据三态决定 ack / basicNack(requeue=false)。
 * {@code requeue=false} 是刻意的——重试走死信 + 延迟重投，不占队头。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "glucopilot.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "glucopilot.mq.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitJobConfig {

    private final JobProperties properties;

    /** 任务交换机（topic）：路由键形如 {@code job.moderation.article}。 */
    @Bean
    public TopicExchange jobExchange() {
        return new TopicExchange(properties.getExchange(), true, false);
    }

    /** 任务死信交换机。 */
    @Bean
    public TopicExchange jobDeadLetterExchange() {
        return new TopicExchange(properties.getDeadLetterExchange(), true, false);
    }

    /** 审核队列：先审后发（文章）与先发后审（评论）共用，靠类型区分。 */
    @Bean
    public Queue moderationJobQueue() {
        return jobQueue(properties.getModerationQueue(), "job.moderation");
    }

    /** 记忆抽取队列。 */
    @Bean
    public Queue memoryJobQueue() {
        return jobQueue(properties.getMemoryQueue(), "job.memory");
    }

    /** OCR 队列。 */
    @Bean
    public Queue ocrJobQueue() {
        return jobQueue(properties.getOcrQueue(), "job.ocr");
    }

    @Bean
    public Queue moderationDlq() {
        return deadLetterQueue(properties.getModerationQueue());
    }

    @Bean
    public Queue memoryDlq() {
        return deadLetterQueue(properties.getMemoryQueue());
    }

    @Bean
    public Queue ocrDlq() {
        return deadLetterQueue(properties.getOcrQueue());
    }

    @Bean
    public Binding moderationBinding() {
        return BindingBuilder.bind(moderationJobQueue()).to(jobExchange()).with("job.moderation.#");
    }

    @Bean
    public Binding memoryBinding() {
        return BindingBuilder.bind(memoryJobQueue()).to(jobExchange()).with("job.memory.#");
    }

    @Bean
    public Binding ocrBinding() {
        return BindingBuilder.bind(ocrJobQueue()).to(jobExchange()).with("job.ocr.#");
    }

    // 死信队列绑 # ：业务队列的 x-dead-letter-routing-key 已经决定了它会落到哪个 dlx 键上，
    // 这里只要保证"进了这个 dlx 的消息都能被对应域的 dlq 接住"
    @Bean
    public Binding moderationDlqBinding() {
        return BindingBuilder.bind(moderationDlq()).to(jobDeadLetterExchange()).with("#");
    }

    @Bean
    public Binding memoryDlqBinding() {
        return BindingBuilder.bind(memoryDlq()).to(jobDeadLetterExchange()).with("#");
    }

    @Bean
    public Binding ocrDlqBinding() {
        return BindingBuilder.bind(ocrDlq()).to(jobDeadLetterExchange()).with("#");
    }

    /**
     * 任务消费者的容器工厂：manual ack + 预取 2 + 不重新入队。
     *
     * <p>并发度不在这里定：不同队列的并发不一样（审核 4 / 记忆 2 / OCR 2），
     * 由各自的 {@code @RabbitListener(concurrency = ...)} 覆盖。
     */
    @Bean(name = "jobContainerFactory")
    public SimpleRabbitListenerContainerFactory jobContainerFactory(ConnectionFactory connectionFactory,
                                                                   MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(Math.max(properties.getPrefetch(), 1));
        // 处理失败由监听器显式 basicNack(requeue=false)，这里的兜底也要保持一致，
        // 否则监听器抛异常时容器会默认 requeue，把消息顶回队头
        factory.setDefaultRequeueRejected(false);
        factory.setMissingQueuesFatal(false);
        return factory;
    }

    /** 业务队列：带死信路由，但**不设** TTL / 长度上限（任务积压时宁可堆积也不要静默丢）。 */
    private Queue jobQueue(String name, String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    private Queue deadLetterQueue(String queue) {
        return QueueBuilder.durable(JobProperties.dlqOf(queue)).build();
    }
}
