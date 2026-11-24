package com.threadfit.glucopilot.community.moderation.mq;

import com.rabbitmq.client.Channel;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.io.IOException;

/**
 * 审核任务队列的消费者：一个方法、一条队列，剩下的全在 {@link com.threadfit.glucopilot.common.mq.job.JobListener} 里。
 *
 * <h2>为什么消费者只有这么几行</h2>
 *
 * 解码、分派、ack/nack 三件事与具体业务无关，三个域（审核 / 记忆 / OCR）完全一样；放在公共壳里之后，
 * 每个域只剩一个 {@code @RabbitListener} 方法，队列名与并发度按各自的负载调。
 *
 * <h2>并发度为什么是 4</h2>
 *
 * 单条审核要调一次模型（秒级），4 路并发足够把"提交后几秒内出结论"这个目标撑住，又不会在一次故障重投时
 * 瞬间打满模型侧限流。它是 {@code ai.concurrency} 之外的一层：那边管"每用户能同时生成几条对话"，这边管"多少个任务在排队审"。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "glucopilot.mq.jobs",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CommunityModerationListener {

    private final com.threadfit.glucopilot.common.mq.job.JobListener listener;

    @RabbitListener(
            queues = "${glucopilot.mq.jobs.moderation-queue:glucopilot.job.moderation}",
            containerFactory = "jobContainerFactory",
            concurrency = "${glucopilot.mq.jobs.moderation-concurrency:4}")
    public void onMessage(Message message, Channel channel) throws IOException {
        listener.handle(message, channel);
    }
}
