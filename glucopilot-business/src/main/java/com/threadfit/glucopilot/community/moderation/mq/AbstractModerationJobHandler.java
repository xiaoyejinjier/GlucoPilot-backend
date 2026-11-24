package com.threadfit.glucopilot.community.moderation.mq;

import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobHandler;
import com.threadfit.glucopilot.common.mq.job.JobOutcome;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationJudge;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

/**
 * 审核任务处理器的公共部分：文章与评论只有"目标类型"不同，认领、判定、落库、重试语义完全一致。
 *
 * <h2>一次处理的生命周期</h2>
 *
 * <ol>
 *   <li>{@link #claim}：按 {@code id(+revision)} 条件抢占。抢不到只说明"别人在审"或"已经审过"，直接 ack；
 *   <li>{@link #handle}：先 {@code load} 读正文（此刻行已经是审核中），再交给 {@link CommunityModerationJudge}；
 *   <li>结论写回走队列的状态机（{@code finish}），PASS 进人工终审、REJECT 驳回、ERROR 转人工。
 * </ol>
 *
 * <h2>模型故障为什么只重试一次</h2>
 *
 * 判定能力把"超时、限流、解析不出结论"统统收敛成 {@code ERROR}，我们无法区分"这条内容有问题"和"模型暂时不可用"。
 * 第一次 ERROR 时把内容放回待审并返回 {@link JobOutcome#RETRYABLE}（消息进死信，约 60 秒后重投一次）；第二次仍 ERROR
 * 就写 {@code AI_ERROR} 收工——故障期间让每条内容在队列里反复重试三次只是持续烧配额，人工作业更快。
 */
abstract class AbstractModerationJobHandler implements JobHandler {

    private final CommunityModerationQueue queue;

    private final CommunityModerationJudge judge;

    AbstractModerationJobHandler(CommunityModerationQueue queue, CommunityModerationJudge judge) {
        this.queue = queue;
        this.judge = judge;
    }

    /** {@code ARTICLE} 或 {@code COMMENT}，与 {@code community_moderation_hit.target_type} 同一口径。 */
    protected abstract String targetType();

    /** 允许多少次"模型出错后自动重来"，不含首次。 */
    protected int extraAttemptsOnError() {
        return 1;
    }

    @Override
    public boolean claim(JobEnvelope envelope) {
        return queue.claimById(targetType(), envelope.refId(), envelope.revision());
    }

    @Override
    public JobOutcome handle(JobEnvelope envelope) {
        CommunityModerationQueue.Job job =
                queue.load(targetType(), envelope.refId(), envelope.revision());
        if (job == null) {
            // 内容已被删除，或改版后版本号对不上：这条消息再重投也没有意义
            return JobOutcome.PERMANENT;
        }
        ModerationResult result = judge.judge(job);
        if (result.verdict() == null) {
            queue.release(targetType(), envelope.refId(), envelope.revision());
            return JobOutcome.RETRYABLE;
        }
        if (result.isError() && envelope.attempt() < extraAttemptsOnError()) {
            queue.release(targetType(), envelope.refId(), envelope.revision());
            return JobOutcome.RETRYABLE;
        }
        // 写回失败（false）只说明状态已被并发改写或版本已变，这与成功等价：结论要么已落库，要么内容已经不归这条消息管
        queue.finish(job, result.verdict(), result.modelCode(), result.promptVersion());
        return JobOutcome.SUCCESS;
    }
}
