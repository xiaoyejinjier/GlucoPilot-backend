package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.ai.business.ocr.service.OcrTaskService;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobHandler;
import com.threadfit.glucopilot.common.mq.job.JobOutcome;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 血糖 OCR 的任务处理器。
 *
 * <h2>认领为什么只查状态</h2>
 *
 * 真正的抢占用的是 {@code OcrTaskMapper.claimTask} 的条件更新（{@code WHERE task_status = 0}），
 * 这里的 {@code countPendingTask} 只是让重复投递在<b>调模型之前</b>就返回：
 * 视觉识别是整个链路里最贵的一步，白跑一次就是一次真实计费。
 *
 * <h2>为什么失败也返回 SUCCESS</h2>
 *
 * 识别失败已经把任务置成 FAILED 并把原因写给用户（"识别服务暂时不可用，请手动输入"），
 * 重投不会让一张模糊照片变得可识别。需要重试的是"进程崩了导致状态卡住"那种情况，
 * 由 {@link OcrReconcileSource} 的回收负责，不在消息层重试。
 */
@Component
@RequiredArgsConstructor
public class OcrRecognizeJobHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OcrRecognizeJobHandler.class);

    private final OcrTaskMapper taskMapper;

    private final OcrTaskService ocrTaskService;

    @Override
    public String type() {
        return JobTypes.OCR_RECOGNIZE;
    }

    @Override
    public boolean claim(JobEnvelope envelope) {
        Long taskId = envelope.refId();
        return taskId != null && taskMapper.countPendingTask(taskId) > 0;
    }

    @Override
    public JobOutcome handle(JobEnvelope envelope) {
        ocrTaskService.recognizeNow(envelope.refId(), envelope.userId());
        LOG.info("OCR 任务处理完毕 taskId={} userId={}", envelope.refId(), envelope.userId());
        return JobOutcome.SUCCESS;
    }
}