package com.threadfit.glucopilot.community.moderation.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobOutcome;
import com.threadfit.glucopilot.common.mq.job.JobTypes;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationJudge;
import com.threadfit.glucopilot.community.moderation.service.CommunityModerationQueue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 审核任务处理器的三态语义。
 *
 * <p>这类逻辑的坑不在"正常路径"，而在四次边界：<b>抢不到</b>（别人在审/已审完）、<b>内容没了</b>（删了或改稿）、
 * <b>模型出错第一次</b>（要放回队列重来一次）、<b>模型出错第二次</b>（不再重试，落 ERROR 交人工）。
 * 每个分支写错都会表现成"任务在 DLQ 里打转"或"违规内容被放行"，所以逐个钉住。
 */
class ModerationJobHandlerTest {

    private static final long NEWS_ID = 42L;

    private static final int REVISION = 3;

    private final CommunityModerationQueue queue = mock(CommunityModerationQueue.class);

    private final CommunityModerationJudge judge = mock(CommunityModerationJudge.class);

    private final ArticleModerationJobHandler handler =
            new ArticleModerationJobHandler(queue, judge);

    private static JobEnvelope envelope(int attempt) {
        return JobEnvelope.of(JobTypes.MODERATION_ARTICLE, NEWS_ID, REVISION, null, null)
                .withAttempt(attempt);
    }

    private static CommunityModerationQueue.Job job() {
        return new CommunityModerationQueue.Job(
                NEWS_ID, REVISION, "标题", "正文", List.of("饮食控糖"));
    }

    @Test
    void 抢不到时不应读正文也不应调模型() {
        when(queue.claimById(CommunityModerationQueue.ARTICLE, NEWS_ID, REVISION))
                .thenReturn(false);

        assertFalse(handler.claim(envelope(0)));

        verify(queue, never()).load(any(), anyLong(), anyInt());
        verify(judge, never()).judge(any());
    }

    @Test
    void 内容已被删除时按永久失败结束() {
        when(queue.load(CommunityModerationQueue.ARTICLE, NEWS_ID, REVISION)).thenReturn(null);

        assertEquals(JobOutcome.PERMANENT, handler.handle(envelope(0)));

        verify(judge, never()).judge(any());
    }

    @Test
    void 模型第一次出错应放回队列并可重试() {
        when(queue.load(CommunityModerationQueue.ARTICLE, NEWS_ID, REVISION)).thenReturn(job());
        when(judge.judge(job()))
                .thenReturn(new ModerationResult(ModerationVerdict.error("超时"), "", ""));

        assertEquals(JobOutcome.RETRYABLE, handler.handle(envelope(0)));

        verify(queue).release(CommunityModerationQueue.ARTICLE, NEWS_ID, REVISION);
        verify(queue, never()).finish(any(), any(), any(), any());
    }

    @Test
    void 模型第二次仍出错应落ERROR收工不再重试() {
        when(queue.load(CommunityModerationQueue.ARTICLE, NEWS_ID, REVISION)).thenReturn(job());
        when(judge.judge(job()))
                .thenReturn(new ModerationResult(ModerationVerdict.error("超时"), "", ""));

        assertEquals(JobOutcome.SUCCESS, handler.handle(envelope(1)));

        verify(queue).finish(eq(job()), any(), any(), any());
        verify(queue, never()).release(any(), anyLong(), any());
    }

    @Test
    void 通过时写回结论且不需要放回队列() {
        when(queue.load(CommunityModerationQueue.ARTICLE, NEWS_ID, REVISION)).thenReturn(job());
        when(judge.judge(job()))
                .thenReturn(
                        new ModerationResult(
                                ModerationVerdict.pass("建议人工核验"),
                                "deepseek-v4-pro",
                                "moderation@v1"));

        assertEquals(JobOutcome.SUCCESS, handler.handle(envelope(0)));

        verify(queue)
                .finish(
                        eq(job()),
                        org.mockito.ArgumentMatchers.argThat(ModerationVerdict::isPass),
                        eq("deepseek-v4-pro"),
                        eq("moderation@v1"));
    }

    @Test
    void 评论处理器使用评论目标类型() {
        CommunityModerationQueue commentQueue = mock(CommunityModerationQueue.class);
        CommentModerationJobHandler commentHandler =
                new CommentModerationJobHandler(commentQueue, judge);
        when(commentQueue.claimById(CommunityModerationQueue.COMMENT, 7L, null)).thenReturn(true);

        assertTrue(
                commentHandler.claim(
                        JobEnvelope.of(JobTypes.MODERATION_COMMENT, 7L, null, 9L, null)));
    }
}
