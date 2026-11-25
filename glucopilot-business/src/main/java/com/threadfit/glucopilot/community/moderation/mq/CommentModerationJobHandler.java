package com.threadfit.glucopilot.community.moderation.mq;

import com.threadfit.glucopilot.common.mq.job.JobTypes;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationJudge;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

import org.springframework.stereotype.Component;

/**
 * 评论初审任务的处理器。
 *
 * <p>评论是<b>先发后审</b>：写入即可见，REJECT 才把 {@code is_hidden} 置 1 藏起来。 因此评论的审核延迟直接体现为"违规内容可见的时长"，比文章更怕排队。
 */
@Component
public class CommentModerationJobHandler extends AbstractModerationJobHandler {

    public CommentModerationJobHandler(CommunityModerationQueue queue, CommunityModerationJudge judge) {
        super(queue, judge);
    }

    @Override
    public String type() {
        return JobTypes.MODERATION_COMMENT;
    }

    @Override
    protected String targetType() {
        return CommunityModerationQueue.COMMENT;
    }
}
