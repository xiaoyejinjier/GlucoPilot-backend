package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.model.entity.AiCallLog;
import com.threadfit.glucopilot.ai.model.mapper.AiCallLogMapper;
import com.threadfit.glucopilot.ai.scene.SceneCode;
import com.threadfit.glucopilot.ai.support.AiCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 异步账单写入器：写库成功、写库失败、队列满丢弃三条路径。
 *
 * <h2>为什么值得单独测"队列满"</h2>
 * 这条策略是<b>有意的功能降级</b>：宁可少一条账单，也不让用户的回复路径去做同步 DB 写。
 * 但它必须"只丢不抛"——一旦抛出去，{@code DynamicChatModel} 的记账异常会把成功的模型调用
 * 变成 500。所以这里把「队列满了会发生什么」钉死。
 */
class AsyncAiCallRecorderTest {

    private AsyncAiCallRecorder recorder;

    @AfterEach
    void tearDown() {
        if (recorder != null) {
            recorder.shutdown();
        }
    }

    private static AiCallRecord record() {
        return AiCallRecord.of(new AiCallContext.Call(SceneCode.CHAT, 27L, 178L), "text",
                "deepseek-v4-pro", "deepseek-v4-pro", new ModelCallUsage(100, 20, 60L, 40L),
                12L, false, true, null);
    }

    @Test
    void 记录应异步落库并带上全部字段() {
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        recorder = new AsyncAiCallRecorder(mapper);

        recorder.record(record());
        assertThat(recorder.awaitIdle(2000)).isTrue();

        verify(mapper, timeout(1000)).insert(any(AiCallLog.class));
        var captor = org.mockito.ArgumentCaptor.forClass(AiCallLog.class);
        verify(mapper).insert(captor.capture());
        AiCallLog row = captor.getValue();
        assertThat(row.getSceneCode()).isEqualTo("CHAT");
        assertThat(row.getUserId()).isEqualTo(27L);
        assertThat(row.getSessionId()).isEqualTo(178L);
        assertThat(row.getChannel()).isEqualTo("text");
        assertThat(row.getModelCode()).isEqualTo("deepseek-v4-pro");
        assertThat(row.getPromptTokens()).isEqualTo(100);
        assertThat(row.getCompletionTokens()).isEqualTo(20);
        assertThat(row.getCacheHitTokens()).isEqualTo(60);
        assertThat(row.getCacheMissTokens()).isEqualTo(40);
        assertThat(row.getStream()).isZero();
        assertThat(row.getOk()).isEqualTo(1);
        assertThat(recorder.droppedCount()).isZero();
        assertThat(recorder.failedCount()).isZero();
    }

    @Test
    void 没有上下文时应记成空场景而不是报错() {
        AiCallLog row = AsyncAiCallRecorder.toRow(AiCallRecord.of(null, "text",
                "deepseek-v4-pro", "deepseek-v4-pro", null, 5L, true, false, "IllegalStateException"));

        assertThat(row.getSceneCode()).isEmpty();
        assertThat(row.getUserId()).isNull();
        assertThat(row.getSessionId()).isNull();
        assertThat(row.getStream()).isEqualTo(1);
        assertThat(row.getOk()).isZero();
        assertThat(row.getErrorCode()).isEqualTo("IllegalStateException");
        // 没有用量时四列都要留空：0 会被报表当成"确实没命中"
        assertThat(row.getPromptTokens()).isNull();
        assertThat(row.getCacheHitTokens()).isNull();
    }

    @Test
    void 写库失败只增计数不抛异常() {
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        doThrow(new IllegalStateException("库挂了")).when(mapper).insert(any(AiCallLog.class));
        recorder = new AsyncAiCallRecorder(mapper);

        recorder.record(record());
        assertThat(recorder.awaitIdle(2000)).isTrue();

        verify(mapper, timeout(1000)).insert(any(AiCallLog.class));
        assertThat(recorder.failedCount()).isEqualTo(1);
    }

    @Test
    void 队列满应丢弃而不是让调用线程去写库() throws Exception {
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        CountDownLatch stuck = new CountDownLatch(1);
        doAnswer(invocation -> {
            // 卡住工作线程，后面的任务就会堆在容量为 1 的队列里
            stuck.await(5, TimeUnit.SECONDS);
            return 1;
        }).when(mapper).insert(any(AiCallLog.class));
        recorder = new AsyncAiCallRecorder(mapper, 1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        try {
            recorder.record(record());   // 被工作线程取走，卡住
            recorder.record(record());   // 进队列
            recorder.record(record());   // 队列满 → 必须被丢弃，且不抛
        } catch (Throwable e) {
            thrown.set(e);
        } finally {
            stuck.countDown();
        }

        assertThat(thrown.get()).isNull();
        assertThat(recorder.droppedCount()).isEqualTo(1);
    }
}
