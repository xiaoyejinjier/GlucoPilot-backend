package com.threadfit.glucopilot.ai.support.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 业务闸门的语义：谁被拦、拦成什么错误码、拦完要不要把已经拿到的名额还回去。
 *
 * <p>这些用例是给"以后加限制的人"看的清单——每加一条限制，都要回答三个问题：
 * 什么情况拒绝、拒绝成 4xx 的哪一个、拒绝时有没有漏放已经持有的资源。
 */
class AiConcurrencyPolicyTest {

    private static AiConcurrencyProperties properties() {
        AiConcurrencyProperties properties = new AiConcurrencyProperties();
        properties.setClusterRoundPermits(2);
        properties.setStreamPermitsPerUser(2);
        properties.setChatPerMinute(3);
        properties.setDailyChatTurns(2);
        properties.setSpeechClusterPermits(1);
        return properties;
    }

    private static AiConcurrencyPolicy policy(InMemoryLeaseGuard guard, AiConcurrencyProperties properties) {
        return new AiConcurrencyPolicy(guard, new InMemoryRateGate(), new InMemoryCacheStore(),
                new InMemoryCounter(), properties, Clock.systemDefaultZone());
    }

    @Test
    void 同一条消息第二次生成应抛冲突并放回集群名额() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        AiConcurrencyPolicy policy = policy(guard, properties());

        String token = policy.acquireRound(7L);
        assertThat(token).isNotNull();

        assertThatThrownBy(() -> policy.acquireRound(7L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 关键：被拒的那次必须把先拿到的集群名额还回去，否则集群名额会被"失败的尝试"慢慢吃光
        assertThat(guard.held("ai:round:cluster")).isEqualTo(1);
        assertThat(guard.held("ai:round:7")).isEqualTo(1);

        policy.releaseRound(7L, token);
        assertThat(guard.held("ai:round:cluster")).isZero();
        assertThat(guard.held("ai:round:7")).isZero();
    }

    @Test
    void 集群名额用尽应抛503而不是409() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        AiConcurrencyPolicy policy = policy(guard, properties());

        policy.acquireRound(1L);
        policy.acquireRound(2L);

        assertThatThrownBy(() -> policy.acquireRound(3L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void 超过每人并发流上限应抛429且不影响已持有的流() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        AiConcurrencyPolicy policy = policy(guard, properties());

        String first = policy.acquireStream(9L);
        String second = policy.acquireStream(9L);

        assertThatThrownBy(() -> policy.acquireStream(9L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(guard.held("ai:stream:user:9")).isEqualTo(2);

        policy.releaseStream(9L, first);
        policy.releaseStream(9L, second);
        assertThat(guard.held("ai:stream:user:9")).isZero();
    }

    @Test
    void 每分钟请求数超限应抛429() {
        AiConcurrencyPolicy policy = policy(new InMemoryLeaseGuard(), properties());

        policy.checkRate(1L);
        policy.checkRate(1L);
        policy.checkRate(1L);

        assertThatThrownBy(() -> policy.checkRate(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    void 每日额度应在跨天后自动恢复() {
        // "现在"钉在今天 23:59:50（与真实日期相对，而不是写死某天——写死会让用例在几天后失效：
        // 计数器的 expireAt 一旦落在过去，就会被判"已过期"，额度永远放行，断言随之失败）
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        Clock beforeMidnight = Clock.fixed(today.atTime(23, 59, 50).atZone(zone).toInstant(), zone);
        InMemoryCounter counter = new InMemoryCounter(beforeMidnight);
        AiConcurrencyPolicy policy = new AiConcurrencyPolicy(new InMemoryLeaseGuard(), new InMemoryRateGate(),
                new InMemoryCacheStore(), counter, properties(), beforeMidnight);

        policy.checkDailyQuota(5L);
        policy.checkDailyQuota(5L);
        assertThatThrownBy(() -> policy.checkDailyQuota(5L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        // 次日：键带日期，天然是新的计数器
        Clock nextDay = Clock.fixed(today.plusDays(1).atTime(0, 0, 10).atZone(zone).toInstant(), zone);
        AiConcurrencyPolicy tomorrow = new AiConcurrencyPolicy(new InMemoryLeaseGuard(), new InMemoryRateGate(),
                new InMemoryCacheStore(), new InMemoryCounter(nextDay), properties(), nextDay);
        tomorrow.checkDailyQuota(5L);
    }

    @Test
    void 语音识别应同时占用个人与集群名额并在释放后归还() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        AiConcurrencyPolicy policy = policy(guard, properties());

        String token = policy.acquireSpeech(3L);
        assertThat(guard.held("ai:speech:user:3")).isEqualTo(1);
        assertThat(guard.held("ai:speech:cluster")).isEqualTo(1);

        // 集群只剩 1 个名额已被占满：另一个人也应被拒，且不能把自己的集群名额漏在里面
        assertThatThrownBy(() -> policy.acquireSpeech(4L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(guard.held("ai:speech:user:4")).isZero();

        policy.releaseSpeech(3L, token);
        assertThat(guard.held("ai:speech:user:3")).isZero();
        assertThat(guard.held("ai:speech:cluster")).isZero();
    }

    @Test
    void 摘要合并标记应只允许一个任务并可在结束后立刻重跑() {
        AiConcurrencyPolicy policy = policy(new InMemoryLeaseGuard(), properties());

        assertThat(policy.markSummaryRunning(11L)).isTrue();
        assertThat(policy.markSummaryRunning(11L)).as("同一会话第二个任务应被合并").isFalse();

        policy.finishSummary(11L);
        assertThat(policy.markSummaryRunning(11L)).isTrue();
    }

    @Test
    void 定时任务独占应只让一个实例拿到() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        AiConcurrencyPolicy policy = policy(guard, properties());

        assertThat(policy.markJob("calllog-cleanup")).isTrue();
        assertThat(policy.markJob("calllog-cleanup")).isFalse();
        assertThat(guard.held("ai:job:calllog-cleanup")).isEqualTo(1);
    }

    @Test
    void 释放空令牌不应抛异常() {
        AiConcurrencyPolicy policy = policy(new InMemoryLeaseGuard(), properties());

        policy.releaseRound(1L, null);
        policy.releaseStream(1L, null);
        policy.releaseSpeech(1L, null);

        assertThat(policy.markJob("x")).isTrue();
        assertThat(Duration.ofSeconds(1)).isPositive();
    }
}
