package com.threadfit.glucopilot.community.moderation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.ai.business.moderation.service.ContentModerationService;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationRequest;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult;
import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 审核节拍器（{@link CommunityModerationWorker}）的接线与失败语义。
 *
 * <p>能力层（拼提示词、调模型、解析输出）收束到 {@code ai.business.moderation} 之后，本类只剩三件事： 什么时候审、审哪条、审完怎么落库。所以这里守的也正是这三点：
 *
 * <ol>
 *   <li><b>队列空就不调模型</b>：后台轮询每 {@code delay} 毫秒一次，空闲时不能产生任何调用；
 *   <li><b>结论原样回填</b>：判定结果、真实模型编码、提示词版本三者都要写进 {@code queue.finish}， 缺一个就没法回答"这条是谁、用哪版提示词判的"；
 *   <li><b>失败不放行</b>：能力层抛异常时兜底成 {@code ERROR}，绝不当成通过（文章不会被自动发布）。
 * </ol>
 */
class CommunityModerationWorkerTest {

    private static ModerationResult passResult() {
        return new ModerationResult(
                ModerationVerdict.pass("建议人工核验"), "deepseek-v4-pro", "moderation@v1");
    }

    @Test
    void 调度器可以独立装配() {
        try (var context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new org.springframework.core.env.MapPropertySource(
                                    "test",
                                    java.util.Map.of(
                                            "community.moderation.initial-delay", "3600000")));
            context.registerBean(
                    CommunityModerationQueue.class, () -> mock(CommunityModerationQueue.class));
            context.registerBean(
                    ContentModerationService.class, () -> mock(ContentModerationService.class));
            context.register(CommunityModerationWorker.class);
            context.refresh();

            assertNotNull(context.getBean(CommunityModerationWorker.class));
            verifyNoInteractions(context.getBean(CommunityModerationQueue.class));
        }
    }

    @Test
    void 队列为空时不调用审核能力() {
        var queue = mock(CommunityModerationQueue.class);
        var moderation = mock(ContentModerationService.class);
        when(queue.claim()).thenReturn(null);

        new CommunityModerationWorker(queue, moderation).runOnce();

        verifyNoInteractions(moderation);
    }

    @Test
    void 审核结论与可追溯信息原样回填() {
        var queue = mock(CommunityModerationQueue.class);
        var moderation = mock(ContentModerationService.class);
        var job = new CommunityModerationQueue.Job(1, 2, "原标题", "正文", List.of());
        when(queue.claim()).thenReturn(job);
        when(moderation.judge(
                        argThat(
                                r ->
                                        "原标题".equals(r.title())
                                                && "正文".equals(r.body())
                                                && !r.isComment())))
                .thenReturn(passResult());

        new CommunityModerationWorker(queue, moderation).runOnce();

        verify(queue)
                .finish(
                        eq(job),
                        argThat(ModerationVerdict::isPass),
                        eq("deepseek-v4-pro"),
                        eq("moderation@v1"));
    }

    @Test
    void 审核能力抛异常时兜底为ERROR且不自动放行() {
        var queue = mock(CommunityModerationQueue.class);
        var moderation = mock(ContentModerationService.class);
        var job = new CommunityModerationQueue.Job(1, 2, "文章标题", "正文", List.of("标签"));
        when(queue.claim()).thenReturn(job);
        when(moderation.judge(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));

        new CommunityModerationWorker(queue, moderation).runOnce();

        verify(queue).finish(eq(job), argThat(ModerationVerdict::isError), eq(""), eq(""));
    }

    @Test
    void 队列不可用时本轮直接跳过() {
        var queue = mock(CommunityModerationQueue.class);
        var moderation = mock(ContentModerationService.class);
        when(queue.claim()).thenThrow(new IllegalStateException("db down"));

        new CommunityModerationWorker(queue, moderation).runOnce();

        verifyNoInteractions(moderation);
    }

    /** 任务摊平成请求：评论只有正文，文章带标题与标签——这是业务侧唯一还需要知道 Job 形状的地方。 */
    @Test
    void 任务按类型摊平成审核请求() {
        ModerationRequest comment =
                CommunityModerationWorker.request(
                        new CommunityModerationQueue.Job(
                                CommunityModerationQueue.COMMENT,
                                9,
                                null,
                                null,
                                "评论正文",
                                List.of()));
        assertTrue(comment.isComment());
        assertEquals("评论正文", comment.body());
        assertTrue(comment.tags().isEmpty());

        ModerationRequest article =
                CommunityModerationWorker.request(
                        new CommunityModerationQueue.Job(
                                1, 1, "标题A", "正文B", List.of("饮食控糖", "type2")));
        assertEquals(ModerationRequest.KIND_ARTICLE, article.kind());
        assertEquals("标题A", article.title());
        assertEquals(List.of("饮食控糖", "type2"), article.tags());
    }
}
