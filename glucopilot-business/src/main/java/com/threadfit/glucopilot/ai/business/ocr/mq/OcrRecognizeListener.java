package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.common.mq.job.JobListener;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;

/**
 * 血糖 OCR 队列的消费入口。
 *
 * <p>并发默认 2：一次识别是一张图（几十 KB 到几 MB）的视觉调用，且用户就在页面上等结果；
 * 再高只会把出向带宽和视觉配额抢成一团。真正的限流在上游的图片上传与每日额度。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "glucopilot.mq.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OcrRecognizeListener {

    private final JobListener jobListener;

    @RabbitListener(
            queues = "${glucopilot.mq.jobs.ocr-queue:glucopilot.job.ocr}",
            containerFactory = "jobContainerFactory",
            concurrency = "${glucopilot.mq.jobs.ocr-concurrency:2}")
    public void onMessage(Message message, Channel channel) throws java.io.IOException {
        jobListener.handle(message, channel);
    }
}