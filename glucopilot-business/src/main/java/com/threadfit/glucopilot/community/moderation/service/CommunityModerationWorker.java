package com.threadfit.glucopilot.community.moderation.service;

import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationRequest;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult;
import com.threadfit.glucopilot.common.mq.job.JobProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 社区初审的<b>轮询兜底</b>：每 {@code community.moderation.delay} 毫秒从队列取一条待审内容审掉。
 *
 * <h2>两条通道的分工</h2>
 *
 * MQ 正常工作时，内容一提交就会收到任务消息，消费者几百毫秒内审完，本类不再抢活；但下面两件事仍由它负责：
 *
 * <ol>
 *   <li><b>僵尸回收</b>：进程在"审核中"崩溃时，那一行不会自己回到待审，必须有人定时打回
 *   <li><b>降级通道</b>：{@code glucopilot.mq.jobs.event-driven=false} 时（本地无 MQ、或 MQ 故障排查期间）
 *       退回纯轮询，行为与改造前完全一致
 * </ol>
 *
 * <h2>审哪条、怎么落库</h2>
 *
 * 出队靠 {@link CommunityModerationQueue#claim()}，落库靠 {@link CommunityModerationQueue#finish} 的状态机
 * （通过进人工终审、拒绝直接驳回、出错转人工）。判定本身在 {@link CommunityModerationJudge}，与 MQ 消费者共用同一份实现。
 *
 * <h2>失败语义</h2>
 *
 * 定时任务不能因为一次意外就停摆，所以任何路径失败都收敛成 {@code ERROR} ——<b>绝不自动放行</b>：文章进 {@code
 * AI_ERROR} 等人工点"重新初审"，评论保持可见但 {@code ai_verdict=ERROR}。
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "community.moderation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CommunityModerationWorker {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(CommunityModerationWorker.class);

    /** 能力层之外的兜底文案：与 {@code ModelContentModerationService.UNAVAILABLE} 同一口径。 */
    static final String UNAVAILABLE = CommunityModerationJudge.UNAVAILABLE;

    private final CommunityModerationQueue queue;

    private final CommunityModerationJudge judge;

    private final JobProperties jobProperties;

    public CommunityModerationWorker(
            CommunityModerationQueue queue,
            CommunityModerationJudge judge,
            JobProperties jobProperties) {
        this.queue = queue;
        this.judge = judge;
        this.jobProperties = jobProperties;
    }

    @Scheduled(
            initialDelayString = "${community.moderation.initial-delay:10000}",
            fixedDelayString = "${community.moderation.delay:5000}")
    public void runOnce() {
        if (jobProperties.isEventDriven()) {
            // MQ 是主通道：这里只做僵尸回收，不再抢活（否则两个消费者会互相抢同一条，日志难对账）
            reclaimStale();
            return;
        }
        CommunityModerationQueue.Job job;
        try {
            job = queue.claim();
        } catch (RuntimeException ex) {
            LOG.warn("社区初审队列暂不可用", ex);
            return;
        }
        if (job == null) {
            return;
        }
        ModerationResult result = judge.judge(job);
        if (result.isError()) {
            LOG.warn(
                    "社区初审未得到可解析结论 id={} revision={} 评论={} 原因={}",
                    job.id(),
                    job.revision(),
                    job.isComment(),
                    result.verdict() == null ? "无结果" : result.verdict().reason());
        }
        queue.finish(job, result.verdict(), result.modelCode(), result.promptVersion());
    }

    private void reclaimStale() {
        try {
            queue.reclaimStale();
        } catch (RuntimeException ex) {
            // 回收失败不能影响下一轮：卡住的行下一轮还会被尝试
            LOG.warn("社区初审僵尸任务回收失败", ex);
        }
    }

    /** 保留原静态入口，供既有单测直接验证字段映射。 */
    static ModerationRequest request(CommunityModerationQueue.Job job) {
        return CommunityModerationJudge.request(job);
    }
}
