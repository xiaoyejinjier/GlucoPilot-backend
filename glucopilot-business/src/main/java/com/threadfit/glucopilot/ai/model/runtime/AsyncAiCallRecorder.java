package com.threadfit.glucopilot.ai.model.runtime;

import com.threadfit.glucopilot.ai.model.entity.AiCallLog;
import com.threadfit.glucopilot.ai.model.mapper.AiCallLogMapper;
import com.threadfit.glucopilot.ai.support.AiCallContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 账单的异步写入器：单线程 + 有界队列，满了就丢。
 *
 * <h2>三条刻意的选择</h2>
 * <ol>
 *   <li><b>绝不 {@code CallerRunsPolicy}</b>：那种策略在队列满时会让调用线程自己去写库——
 *       等于在用户的回复路径上插一次同步 DB 写，库一慢，聊天就卡。丢一条账单的代价是
 *       "少统计一次调用"，卡住用户的代价是"整个功能不可用"，两者不能相提并论。</li>
 *   <li><b>单线程</b>：账单写入是纯 INSERT，并发写在 MySQL 上只会互相争锁；一个线程足够
 *       跑几万行/分钟，也天然把它们排成固定顺序。</li>
 *   <li><b>全部吞异常</b>：本类是观测设施，任何失败都只能落进日志与计数器，不能冒泡回
 *       {@code DynamicChatModel}——否则"记账失败"会变成"模型调用失败"，用户直接看到 500。</li>
 * </ol>
 *
 * <h2>可观测性</h2>
 * 丢弃与失败各有一个计数器，WARN 每分钟最多一条（队列持续满时逐条打会把日志淹掉，
 * 反而看不见别的问题）。计数器的正确用法是<u>看趋势</u>：偶尔丢几条无所谓，持续增长说明
 * 该调大队列或查库了。
 */
@Slf4j
@Component
public class AsyncAiCallRecorder implements AiCallRecorder {

    /** 队列容量：按「每秒 20 次调用 × 100 秒」估算，足够扛一次库抖动。 */
    static final int DEFAULT_QUEUE_CAPACITY = 2000;

    private static final long WARN_INTERVAL_MS = 60_000L;

    private final AiCallLogMapper mapper;
    private final ThreadPoolExecutor executor;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong warnedAt = new AtomicLong();

    /**
     * 容器用的构造器（单参版本）。
     *
     * <p>两个 public 构造器会让 Spring 无法猜"该用哪个"，必须显式标 {@code @Autowired}，
     * 否则启动直接报 {@code No default constructor found}——这就是本类第一次打包启动时踩的坑。</p>
     */
    @Autowired
    public AsyncAiCallRecorder(AiCallLogMapper mapper) {
        this(mapper, DEFAULT_QUEUE_CAPACITY);
    }

    /** 容量可注入，供单测把队列撑满来验证"满了只丢不抛"。 */
    public AsyncAiCallRecorder(AiCallLogMapper mapper, int queueCapacity) {
        this.mapper = mapper;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(queueCapacity, 1)),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-call-log");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void record(AiCallRecord record) {
        if (record == null) {
            return;
        }
        AiCallLog row;
        try {
            row = toRow(record);
        } catch (RuntimeException e) {
            // 映射本身出错（不该发生）：记一笔失败，不抛
            failed.incrementAndGet();
            warnThrottled("账单行转换失败", e);
            return;
        }
        try {
            executor.execute(() -> insert(row));
        } catch (RejectedExecutionException e) {
            // 队列满：丢，但必须留痕，否则"报表少了一半"永远查不出原因
            long total = dropped.incrementAndGet();
            warnThrottled("账单队列已满，丢弃本次记录（累计丢弃 " + total + " 条）", null);
        }
    }

    /** 队列里丢弃过的条数（丢 != 失败：前者是主动放弃，后者是写库报错）。 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 写库失败条数。 */
    public long failedCount() {
        return failed.get();
    }

    /** 等队列里的账单写完（单测与优雅停机用）。 */
    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 0);
        while (System.currentTimeMillis() < deadline) {
            if (executor.getQueue().isEmpty() && executor.getActiveCount() == 0) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return executor.getQueue().isEmpty() && executor.getActiveCount() == 0;
    }

    /**
     * 停机：先停止收新任务，最多等 2 秒把已入队的写完。
     *
     * <p>为什么要等：最后几条往往正好是"这一轮对话"的账单，丢了就会在报表里留下
     * "消息有、账单没有"的空洞。2 秒是权衡——再长就该怀疑库有问题了，不该拖住停机。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                int left = executor.getQueue().size();
                executor.shutdownNow();
                log.warn("账单队列未在 2 秒内写完，已强制停止（剩余 {} 条）", left);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void insert(AiCallLog row) {
        try {
            mapper.insert(row);
        } catch (RuntimeException e) {
            long total = failed.incrementAndGet();
            warnThrottled("账单写入失败（累计失败 " + total + " 条）", e);
        }
    }

    static AiCallLog toRow(AiCallRecord record) {
        AiCallLog row = new AiCallLog();
        AiCallContext.Call context = record.context();
        row.setSceneCode(context == null || context.scene() == null ? "" : context.scene().name());
        row.setUserId(context == null ? null : context.userId());
        row.setSessionId(context == null ? null : context.sessionId());
        row.setChannel(record.channel());
        row.setModelCode(record.modelCode());
        row.setModelEndpoint(record.modelEndpoint());
        ModelCallUsage usage = record.usage();
        if (usage != null) {
            row.setPromptTokens(usage.promptTokens());
            row.setCompletionTokens(usage.completionTokens());
            row.setCacheHitTokens(intOf(usage.cacheHitTokens()));
            row.setCacheMissTokens(intOf(usage.cacheMissTokens()));
        }
        row.setLatencyMs((int) Math.min(record.latencyMs(), Integer.MAX_VALUE));
        row.setStream(record.stream() ? 1 : 0);
        row.setOk(record.ok() ? 1 : 0);
        row.setErrorCode(record.errorCode());
        return row;
    }

    private static Integer intOf(Long value) {
        if (value == null) {
            return null;
        }
        return (int) Math.min(Math.max(value, 0L), Integer.MAX_VALUE);
    }

    /** 每分钟最多一条 WARN：队列持续满时逐条打会把日志淹掉。 */
    private void warnThrottled(String message, RuntimeException error) {
        long now = System.currentTimeMillis();
        long last = warnedAt.get();
        if (now - last < WARN_INTERVAL_MS || !warnedAt.compareAndSet(last, now)) {
            return;
        }
        if (error == null) {
            log.warn(message);
        } else {
            log.warn(message, error);
        }
    }
}
