package com.threadfit.glucopilot.ai.memory.mq;

import com.threadfit.glucopilot.common.mq.job.JobListener;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;

/**
 * 长期记忆抽取队列的消费入口。
 *
 * <p>只做转发：解析、三态判定、ack/nack 都在公共的 {@link JobListener} 里，避免每个域各写一份
 * （手动 ack 的两种错法——"先 ack 再干活"和"失败还 requeue"——都是在这里最容易犯的）。
 *
 * <p>并发默认 2：一次抽取是一个窗口 ≤40 条消息的模型调用，3 个并发就够把 4 vCPU 的库拖到抖动；
 * 而每个会话同时只会有一条任务（单飞标记），再高对延迟没有帮助。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "glucopilot.mq.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryExtractListener {

    private final JobListener jobListener;

    @RabbitListener(
            queues = "${glucopilot.mq.jobs.memory-queue:glucopilot.job.memory}",
            containerFactory = "jobContainerFactory",
            concurrency = "${glucopilot.mq.jobs.memory-concurrency:2}")
    public void onMessage(Message message, Channel channel) throws java.io.IOException {
        jobListener.handle(message, channel);
    }
}