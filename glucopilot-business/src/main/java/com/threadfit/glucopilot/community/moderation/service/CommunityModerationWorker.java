package com.threadfit.glucopilot.community.moderation.service;

import com.threadfit.glucopilot.ai.business.moderation.service.ContentModerationService;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationRequest;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 社区初审的<b>节拍器</b>：每 {@code community.moderation.delay} 毫秒从队列取一条待审内容，交给 AI 审核能力，再把结论写回。
 *
 * <h2>这里只剩业务侧的三件事</h2>
 *
 * <ol>
 *   <li>什么时候审（{@link Scheduled} 的节拍）
 *   <li>审哪条（{@link CommunityModerationQueue#claim()} 的出队规则）
 *   <li>审完怎么落库（{@link CommunityModerationQueue#finish} 的状态机：通过进人工终审、拒绝直接驳回、出错转人工）
 * </ol>
 *
 * 拼提示词、定场景参数、调模型、解析输出这些「AI 的活」已经收束到 {@code
 * ai.business.moderation}，本类只负责把队列任务摊平成 {@link ModerationRequest} 并回填 {@link ModerationResult}。
 *
 * <h2>失败语义</h2>
 *
 * 审核能力承诺不抛异常（失败返回 {@code ERROR}），这里再兜一层：定时任务不能因为一次意外就停摆。 无论哪条路径失败，结论都是 {@code ERROR} ——<b>绝不自动放行</b>：文章进 {@code
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
    static final String UNAVAILABLE = "AI 初审暂不可用，请重新初审；未自动发布文章";

    private final CommunityModerationQueue queue;

    private final ContentModerationService moderation;

    public CommunityModerationWorker(
            CommunityModerationQueue queue, ContentModerationService moderation) {
        this.queue = queue;
        this.moderation = moderation;
    }

    @Scheduled(
            initialDelayString = "${community.moderation.initial-delay:10000}",
            fixedDelayString = "${community.moderation.delay:5000}")
    public void runOnce() {
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
        ModerationResult result;
        try {
            result = moderation.judge(request(job));
        } catch (RuntimeException ex) {
            result = new ModerationResult(ModerationVerdict.error(UNAVAILABLE), "", "");
            LOG.warn("社区初审被意外中断 articleId={} revision={}", job.id(), job.revision(), ex);
        }
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

    /** 把队列任务摊平成审核请求：字段映射是业务侧的知识，能力侧不该知道 {@code Job} 长什么样。 */
    static ModerationRequest request(CommunityModerationQueue.Job job) {
        return job.isComment()
                ? ModerationRequest.comment(job.body())
                : ModerationRequest.article(job.title(), job.tags(), job.body());
    }
}
