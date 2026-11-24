package com.threadfit.glucopilot.common.mq.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

/**
 * 消费端 ack/nack 的唯一出口。
 *
 * <p>这里守的是「不要用 requeue=true」这条纪律：可恢复失败必须走 DLX（由重投器延迟重投）， 否则一条毒消息会把队头堵死；而解码失败属于永久失败，直接进 DLQ 且计入
 * dropped 指标（否则脏消息会静默堆积）。
 */
class JobListenerTest {

    private static final long DELIVERY_TAG = 7L;

    private final JobEnvelopeCodec codec = mock(JobEnvelopeCodec.class);

    private final JobDispatcher dispatcher = mock(JobDispatcher.class);

    private final JobMetrics metrics = new JobMetrics();

    private final JobListener listener = new JobListener(dispatcher, codec, metrics);

    private final Channel channel = mock(Channel.class);

    private static Message message() {
        return MessageBuilder.withBody("{}".getBytes()).setDeliveryTag(DELIVERY_TAG).build();
    }

    @Test
    void 解码失败应丢弃并计数() throws Exception {
        when(codec.decode(any())).thenReturn(null);

        listener.handle(message(), channel);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
        verify(dispatcher, never()).dispatch(any());
        org.junit.jupiter.api.Assertions.assertEquals(1, metrics.snapshot().dropped());
    }

    @Test
    void 成功应ack() throws Exception {
        when(codec.decode(any())).thenReturn(JobEnvelope.of("ocr.recognize", 1L, null, null, null));
        when(dispatcher.dispatch(any())).thenReturn(JobOutcome.SUCCESS);

        listener.handle(message(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void 可恢复失败应nack且不重新入队() throws Exception {
        when(codec.decode(any())).thenReturn(JobEnvelope.of("ocr.recognize", 1L, null, null, null));
        when(dispatcher.dispatch(any())).thenReturn(JobOutcome.RETRYABLE);

        listener.handle(message(), channel);

        // 第三个参数是 requeue：必须为 false（交 DLX 延迟重投，不能塞回队头）
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void 分派抛未预期异常应视为可恢复失败() throws Exception {
        when(codec.decode(any())).thenReturn(JobEnvelope.of("ocr.recognize", 1L, null, null, null));
        when(dispatcher.dispatch(any())).thenThrow(new IllegalStateException("boom"));

        listener.handle(message(), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }
}
