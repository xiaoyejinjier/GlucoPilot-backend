package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobProperties;
import com.threadfit.glucopilot.common.mq.job.JobPublisher;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 血糖 OCR 任务的派发口。
 *
 * <h2>为什么返回 boolean</h2>
 *
 * 与记忆抽取同理：识别在此之前由本地线程池跑，队列是可选替代。队列关闭或这一刻发不出去时，
 * 调用方退回线程池至少还能出结果；返回 false 而不是静默丢，是为了让"到底谁跑的"在代码里可读。
 *
 * <h2>为什么派发失败不影响 create 的返回值</h2>
 *
 * 任务行已经落库、状态是 PENDING，用户拿到 taskId 就能轮询详情；补扫每分钟会把它再派一遍。
 * 把投递失败变成异常只会让用户看到 500，而图片和任务都已经存好了。
 */
@Component
@RequiredArgsConstructor
public class OcrTaskJobs {

    private static final Logger LOG = LoggerFactory.getLogger(OcrTaskJobs.class);

    private final JobPublisher publisher;

    private final JobProperties jobProperties;

    /**
     * @return {@code true} 已交给任务队列；{@code false} 调用方应退回本地线程池
     */
    public boolean publish(Long userId, Long taskId) {
        if (taskId == null) {
            return false;
        }
        if (!jobProperties.isEventDriven()) {
            LOG.debug("任务通道已关闭（event-driven=false），OCR 退回本地线程 taskId={}", taskId);
            return false;
        }
        try {
            publisher.publish(JobEnvelope.of(JobTypes.OCR_RECOGNIZE, taskId, null, userId, null));
            return true;
        } catch (RuntimeException e) {
            LOG.warn("OCR 任务派发失败，退回本地线程 taskId={}", taskId, e);
            return false;
        }
    }
}