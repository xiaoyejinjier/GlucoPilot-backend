package com.threadfit.glucopilot.community.moderation.mq;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobProperties;
import com.threadfit.glucopilot.common.mq.job.JobPublisher;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 社区审核的<b>任务派发口</b>：把"又有内容待审"这件事变成一条任务消息。
 *
 * <h2>为什么派发失败不能影响主流程</h2>
 *
 * 用户发文章/评论的主流程已经写库成功，此时投递失败只意味着"这条内容要晚几秒才被审"。把它变成异常会让用户看到 500，
 * 而正确性由两处兜底保证：{@code JobPublisher} 在事务提交后重试一次，{@link CommunityModerationReconcileSource
 * 补扫}每 5 分钟把漏掉的待审内容再派一遍。
 *
 * <h2>为什么 event-driven 关闭时什么都不做</h2>
 *
 * 那个开关的语义是"改用轮询"。两条通道同时开会让同一条内容被抢两次（一次被消费者、一次被轮询）， 日志里出现两条结论、只写回一条，排查时非常费解。
 */
@Component
@RequiredArgsConstructor
public class CommunityModerationJobs {

    private static final Logger LOG = LoggerFactory.getLogger(CommunityModerationJobs.class);

    private final JobPublisher publisher;

    private final JobProperties jobProperties;

    /**
     * 文章进入待审后派发。
     *
     * @param revision 提交时的版本号；消费端会拿它比对，改稿后的旧消息因此自动作废
     */
    public void articleSubmitted(long newsId, Integer revision) {
        publish(JobTypes.MODERATION_ARTICLE, newsId, revision, null);
    }

    /** 评论写入后派发（先发后审：评论此刻已经可见，审完才可能被隐藏）。 */
    public void commentCreated(long commentId, Long userId) {
        publish(JobTypes.MODERATION_COMMENT, commentId, null, userId);
    }

    private void publish(String type, long refId, Integer revision, Long userId) {
        if (!jobProperties.isEventDriven()) {
            LOG.debug("任务通道已关闭（event-driven=false），改由轮询处理 type={} refId={}", type, refId);
            return;
        }
        try {
            publisher.publish(JobEnvelope.of(type, refId, revision, userId, null));
        } catch (RuntimeException e) {
            LOG.warn("审核任务派发失败，等补扫重发 type={} refId={} revision={}", type, refId, revision, e);
        }
    }
}
