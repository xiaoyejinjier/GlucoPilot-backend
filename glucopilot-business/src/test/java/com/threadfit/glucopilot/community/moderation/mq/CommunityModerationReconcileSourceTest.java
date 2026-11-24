package com.threadfit.glucopilot.community.moderation.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobTypes;
import com.threadfit.glucopilot.community.moderation.mapper.CommunityModerationMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 补扫源的映射语义。
 *
 * <p>两条容易错的地方都在这里钉住：<b>列名大小写</b>（H2 会把列名转成大写，MySQL 原样返回，只认一种写法必然在某一侧拿到 null）
 * 与 <b>宽限期</b>（`before` 必须真的传下去，否则刚提交、正在被正常派发的任务会被再投一遍）。
 */
class CommunityModerationReconcileSourceTest {

    private final CommunityModerationMapper mapper = mock(CommunityModerationMapper.class);

    private final CommunityModerationReconcileSource source =
            new CommunityModerationReconcileSource(mapper);

    @Test
    void 待审文章应映射成带版本号的任务() {
        // H2 风格的大写列名：只认小写键的实现会拿到 null
        when(mapper.pendingArticleJobs(any(), anyInt()))
                .thenReturn(List.of(Map.of("NEWS_ID", 42L, "REVISION", 3)));

        List<JobEnvelope> jobs = source.pending(20);

        assertEquals(1, jobs.size());
        assertEquals(JobTypes.MODERATION_ARTICLE, jobs.get(0).type());
        assertEquals(42L, jobs.get(0).refId());
        assertEquals(3, jobs.get(0).revision());
        assertEquals("reconcile", jobs.get(0).reason());
    }

    @Test
    void 待审评论也应被扫出来且不带版本号() {
        when(mapper.pendingCommentJobs(any(), anyInt()))
                .thenReturn(List.of(Map.of("ID", 7L, "USER_ID", 9L)));

        List<JobEnvelope> jobs = source.pending(20);

        assertEquals(1, jobs.size());
        assertEquals(JobTypes.MODERATION_COMMENT, jobs.get(0).type());
        assertEquals(7L, jobs.get(0).refId());
        assertEquals(9L, jobs.get(0).userId());
    }

    @Test
    void 两类都为空时应返回空列表而不是null() {
        when(mapper.pendingArticleJobs(any(), anyInt())).thenReturn(List.of());
        when(mapper.pendingCommentJobs(any(), anyInt())).thenReturn(List.of());

        assertTrue(source.pending(20).isEmpty());
    }

    @Test
    void 宽限期应传一个过去的时间且带上批量上限() {
        when(mapper.pendingArticleJobs(any(), anyInt())).thenReturn(List.of());
        when(mapper.pendingCommentJobs(any(), anyInt())).thenReturn(List.of());

        source.pending(20);

        // 只校验「传的是过去的时间 + 上限原样透传」，具体宽限多少由 mapper 的 SQL 语句承担
        org.mockito.Mockito.verify(mapper)
                .pendingArticleJobs(
                        org.mockito.ArgumentMatchers.argThat(
                                t -> t != null && t.isBefore(LocalDateTime.now())),
                        org.mockito.ArgumentMatchers.eq(20));
        org.mockito.Mockito.verify(mapper)
                .pendingCommentJobs(
                        org.mockito.ArgumentMatchers.argThat(
                                t -> t != null && t.isBefore(LocalDateTime.now())),
                        org.mockito.ArgumentMatchers.eq(20));
    }

    @Test
    void 宽限阈值必须用数据库时钟而不是进程时钟() {
        // 线上真实事故：ai_claimed_at/created_at 是数据库 CURRENT_TIMESTAMP（UTC 会话）写的，
        // 进程是 +08，拿 LocalDateTime.now() 当阈值等于「刚提交就算超时」，补扫会把在跑的任务再投一遍。
        LocalDateTime dbNow = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(mapper.selectDbNow()).thenReturn(dbNow);
        when(mapper.pendingArticleJobs(any(), anyInt())).thenReturn(List.of());
        when(mapper.pendingCommentJobs(any(), anyInt())).thenReturn(List.of());

        source.pending(20);

        assertEquals(dbNow.minusMinutes(1), capturedBefore());
    }

    /** 取两次调用传下去的 `before`（两次必须都是同一个数据库时钟算出来的值）。 */
    private LocalDateTime capturedBefore() {
        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(mapper)
                .pendingArticleJobs(captor.capture(), org.mockito.ArgumentMatchers.eq(20));
        org.mockito.Mockito.verify(mapper)
                .pendingCommentJobs(captor.capture(), org.mockito.ArgumentMatchers.eq(20));
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
        return captor.getAllValues().get(0);
    }
}
