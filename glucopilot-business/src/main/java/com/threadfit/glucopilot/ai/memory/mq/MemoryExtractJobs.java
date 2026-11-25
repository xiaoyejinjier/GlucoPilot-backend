package com.threadfit.glucopilot.ai.memory.mq;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobProperties;
import com.threadfit.glucopilot.common.mq.job.JobPublisher;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 长期记忆抽取的<b>任务派发口</b>。
 *
 * <h2>为什么这里返回 boolean 而不是像审核那样"发出去就不管"</h2>
 *
 * 审核是"必须先派发、否则内容不会被审"，所以派发失败只记日志、靠补扫；而记忆抽取在此之前一直由本地线程池执行，
 * 任务队列是<b>可选替代</b>：队列没开、或者这一刻发不出去，退回线程池至少还能抽出记忆。
 * 所以这里把"有没有交给队列"如实返回给调用方，由它决定要不要退回本地执行。
 *
 * <h2>为什么 event-driven 关闭时返回 false</h2>
 *
 * 那个开关的语义是"改用进程内执行"。如果关掉队列却仍然返回 true，记忆就再也没人抽了。
 */
@Component
@RequiredArgsConstructor
public class MemoryExtractJobs {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryExtractJobs.class);

    private final JobPublisher publisher;

    private final JobProperties jobProperties;

    /**
     * 把"这个会话该抽记忆了"变成一条任务。
     *
     * @return {@code true} 已交给任务队列；{@code false} 调用方应退回本地线程池
     */
    public boolean publish(Long userId, Long sessionId, String reason) {
        if (sessionId == null) {
            return false;
        }
        if (!jobProperties.isEventDriven()) {
            LOG.debug("任务通道已关闭（event-driven=false），记忆抽取退回本地线程 sessionId={}", sessionId);
            return false;
        }
        try {
            publisher.publish(JobEnvelope.of(JobTypes.MEMORY_EXTRACT, sessionId, null, userId, reason));
            return true;
        } catch (RuntimeException e) {
            LOG.warn("记忆抽取任务派发失败，退回本地线程 sessionId={} reason={}", sessionId, reason, e);
            return false;
        }
    }
}