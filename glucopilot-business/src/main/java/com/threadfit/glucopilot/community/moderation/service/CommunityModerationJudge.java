package com.threadfit.glucopilot.community.moderation.service;

import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationRequest;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict;
import com.threadfit.glucopilot.ai.business.moderation.service.ContentModerationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 把队列任务摊平成审核请求、再把能力的返回值兜成 {@link ModerationResult} 的那一层。
 *
 * <h2>为什么从 Worker 里抽出来</h2>
 *
 * 同一条内容现在有两条入口：MQ 消费者（主通道）与轮询兜底（{@code event-driven=false} 时）。 「怎么审」只能有一份实现，
 * 否则两边对"能力抛异常时该返回什么"的处理会慢慢分叉——这类分叉在事故复盘时最难解释。
 *
 * <h2>失败语义</h2>
 *
 * 审核能力承诺不抛异常（失败返回 {@code ERROR}），这里再兜一层：能力内部出了意外也不能让调用方崩掉。 无论哪条路径失败，结论都是 {@code ERROR}
 * ——<b>绝不自动放行</b>。
 */
@Component
@RequiredArgsConstructor
public class CommunityModerationJudge {

    private static final Logger LOG = LoggerFactory.getLogger(CommunityModerationJudge.class);

    /** 能力层之外的兜底文案：与 {@code ModelContentModerationService.UNAVAILABLE} 同一口径。 */
    public static final String UNAVAILABLE = "AI 初审暂不可用，请重新初审；未自动发布文章";

    private final ContentModerationService moderation;

    /** 审一条内容；不抛异常。 */
    public ModerationResult judge(CommunityModerationQueue.Job job) {
        try {
            return moderation.judge(request(job));
        } catch (RuntimeException ex) {
            LOG.warn("社区初审被意外中断 id={} revision={}", job.id(), job.revision(), ex);
            return new ModerationResult(ModerationVerdict.error(UNAVAILABLE), "", "");
        }
    }

    /** 字段映射是业务侧的知识，能力侧不该知道 {@code Job} 长什么样。 */
    public static ModerationRequest request(CommunityModerationQueue.Job job) {
        return job.isComment()
                ? ModerationRequest.comment(job.body())
                : ModerationRequest.article(job.title(), job.tags(), job.body());
    }
}
