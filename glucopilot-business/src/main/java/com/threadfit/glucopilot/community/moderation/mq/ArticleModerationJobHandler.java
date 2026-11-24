package com.threadfit.glucopilot.community.moderation.mq;

import com.threadfit.glucopilot.common.mq.job.JobTypes;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationJudge;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

import org.springframework.stereotype.Component;

/**
 * 文章初审任务的处理器。
 *
 * <p>文章是<b>先审后发</b>：这块内容作者自己能看到，别人看不到，审完 PASS 才进人工终审、REJECT 直接驳回。
 * 所以文章的拥塞代价比评论高——用户在等自己的文章能不能发出去。
 */
@Component
public class ArticleModerationJobHandler extends AbstractModerationJobHandler {

    public ArticleModerationJobHandler(CommunityModerationQueue queue, CommunityModerationJudge judge) {
        super(queue, judge);
    }

    @Override
    public String type() {
        return JobTypes.MODERATION_ARTICLE;
    }

    @Override
    protected String targetType() {
        return CommunityModerationQueue.ARTICLE;
    }
}
