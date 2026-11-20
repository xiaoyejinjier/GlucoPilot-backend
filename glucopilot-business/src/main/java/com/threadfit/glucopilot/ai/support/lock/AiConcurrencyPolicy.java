package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 业务侧的并发闸门：把"这条消息正在生成"、"这个人聊太快"、"今天额度用完了"这些业务语义，
 * 翻译成 Redis 原语的具体调用。
 *
 * <h2>为什么要单独一层</h2>
 * 如果让调用方各写各的 key，会出现三种腐烂：同一件事用两个 key（漏拦）、阈值散落在业务代码里
 * （改一次要翻五个文件）、错误码各写各的（客户端无法统一处理）。集中在这里之后，
 * 新增一条限制只需要加一个方法，调用方只说"我要生成这一条"。
 *
 * <h2>为什么把「轮次」和「流许可」分开</h2>
 * 它们要防的不是同一件事：轮次是"同一条助手消息被生成两次"（幂等问题，409），
 * 流许可是"一个人同时开太多 SSE"（资源问题，429）。合并成一个限制，
 * 要么让幂等语义丢精度，要么让一个人开第二条会话就被拦。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConcurrencyPolicy {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final AiLeaseGuard leaseGuard;

    private final AiRateGate rateGate;

    private final AiCacheStore cacheStore;

    private final AiCounter counter;

    private final AiConcurrencyProperties properties;

    private final Clock clock;

    /**
     * 抢占「这条助手消息的生成权」。
     *
     * @return 令牌，调用方必须在结束时原样还给 {@link #releaseRound}
     * @throws BizException 409：已有请求在生成同一条消息（或集群生成数已达上限时 503）
     */
    public String acquireRound(long assistantMsgId) {
        String clusterToken = leaseGuard.tryAcquire("ai:round:cluster",
                properties.getClusterRoundPermits(), Duration.ofSeconds(properties.getRoundLeaseSeconds()));
        if (clusterToken == null) {
            // 集群整体打满：不是这个用户的问题，也不是重试一次就能好的（503 而非 409）
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "当前对话请求较多，请稍后再试");
        }
        String token = leaseGuard.tryAcquire("ai:round:" + assistantMsgId, 1,
                Duration.ofSeconds(properties.getRoundLeaseSeconds()));
        if (token == null) {
            leaseGuard.release("ai:round:cluster", clusterToken);
            throw new BizException(ErrorCode.CONFLICT, "这一条正在生成中，请 3 秒后再试");
        }
        return token + "|" + clusterToken;
    }

    /** 释放轮次与集群名额（令牌由 {@link #acquireRound} 拼成 "round|cluster"）。 */
    public void releaseRound(long assistantMsgId, String token) {
        if (token == null) {
            return;
        }
        int split = token.indexOf('|');
        String roundToken = split < 0 ? token : token.substring(0, split);
        leaseGuard.release("ai:round:" + assistantMsgId, roundToken);
        if (split >= 0) {
            leaseGuard.release("ai:round:cluster", token.substring(split + 1));
        }
    }

    /** 抢占一个「并发流」名额：每个人最多 {@code stream-permits-per-user} 条。 */
    public String acquireStream(long userId) {
        String token = leaseGuard.tryAcquire("ai:stream:user:" + userId,
                properties.getStreamPermitsPerUser(), Duration.ofSeconds(properties.getStreamLeaseSeconds()));
        if (token == null) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "同时进行的对话过多（上限 " + properties.getStreamPermitsPerUser() + " 条），请先等其中一条结束");
        }
        return token;
    }

    public void releaseStream(long userId, String token) {
        leaseGuard.release("ai:stream:user:" + userId, token);
    }

    /** 每分钟请求数上限：令牌桶，超出直接 429。 */
    public void checkRate(long userId) {
        boolean allowed = rateGate.tryAcquire("ai:rate:chat:" + userId,
                properties.getChatPerMinute(), Duration.ofMinutes(1));
        if (!allowed) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "发送太频繁（每分钟上限 " + properties.getChatPerMinute() + " 次），请稍后再试");
        }
    }

    /** 每日额度：按自然日计数，次日 00:00 过期。 */
    public void checkDailyQuota(long userId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.systemDefault()));
        String key = "ai:quota:chat:" + userId + ":" + DAY.format(today);
        long used = counter.increment(key, 1, startOfNextDay(today));
        if (used > properties.getDailyChatTurns()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "今天的对话次数已用完（每天 " + properties.getDailyChatTurns() + " 轮），明天再来吧");
        }
    }

    /** 语音识别：每个人一条 + 集群总量两把租约，返回 "user|cluster"。 */
    public String acquireSpeech(long userId) {
        String cluster = leaseGuard.tryAcquire("ai:speech:cluster",
                properties.getSpeechClusterPermits(), Duration.ofSeconds(properties.getSpeechLeaseSeconds()));
        if (cluster == null) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "语音识别排队中，请稍后再试");
        }
        String mine = leaseGuard.tryAcquire("ai:speech:user:" + userId, 1,
                Duration.ofSeconds(properties.getSpeechLeaseSeconds()));
        if (mine == null) {
            leaseGuard.release("ai:speech:cluster", cluster);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "正在识别上一段语音，请稍候");
        }
        return mine + "|" + cluster;
    }

    public void releaseSpeech(long userId, String token) {
        if (token == null) {
            return;
        }
        int split = token.indexOf('|');
        leaseGuard.release("ai:speech:user:" + userId, split < 0 ? token : token.substring(0, split));
        if (split >= 0) {
            leaseGuard.release("ai:speech:cluster", token.substring(split + 1));
        }
    }

    /**
     * 标记「这个会话正在跑摘要」。
     *
     * @return true 表示本次抢到，可以开跑；false 表示已有人跑，静默跳过（不是错误）
     */
    public boolean markSummaryRunning(long sessionId) {
        return cacheStore.putIfAbsent("ai:summary:run:" + sessionId, "running", "1",
                Duration.ofSeconds(properties.getJobDedupSeconds()));
    }

    public boolean markExtractRunning(long sessionId) {
        return cacheStore.putIfAbsent("ai:extract:run:" + sessionId, "running", "1",
                Duration.ofSeconds(properties.getJobDedupSeconds()));
    }

    /** 摘要跑完（无论成功失败）必须清标记，否则同一会话 10 分钟内无法再压。 */
    public void finishSummary(long sessionId) {
        cacheStore.delete("ai:summary:run:" + sessionId, "running");
    }

    public void finishExtract(long sessionId) {
        cacheStore.delete("ai:extract:run:" + sessionId, "running");
    }

    /** 定时任务独占：抢不到说明别的实例正在跑，本实例跳过。 */
    public boolean markJob(String name) {
        return leaseGuard.tryAcquire("ai:job:" + name, 1,
                Duration.ofSeconds(properties.getJobDedupSeconds())) != null;
    }

    private static java.time.Instant startOfNextDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
