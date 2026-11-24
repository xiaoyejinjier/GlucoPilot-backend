package com.threadfit.glucopilot.common.mq.job;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 任务队列的运行计数（发布 / 处理 / 重投 / 丢弃）。
 *
 * <h2>为什么用 {@link LongAdder} 而不是 {@code AtomicLong}</h2>
 * 这些计数在每次发消息、每次消费时都要加一次，而且只增不减、只读快照。
 * {@code LongAdder} 在这种「多写少读」的场景下比 `AtomicLong` 少很多 CAS 冲突；
 * 代价是 `sum()` 不保证是某一瞬间的精确值——对观测来说完全够用。
 *
 * <h2>为什么不做成 Micrometer 指标</h2>
 * 项目没有接 Prometheus 端点，暴露给管理端也还不在本批范围。先把计数留在本地，
 * 由日志与 `/actuator/health` 之外的排查手段读取；等真的要做看板时，
 * 再把这些方法改成注册 `Counter` 即可，调用点不用动。
 */
@Component
public class JobMetrics {

    private final LongAdder published = new LongAdder();
    private final LongAdder handled = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    /** 投递成功一次（含补扫与重投）。 */
    public void countPublished() {
        published.increment();
    }

    /** 消费并落定一次（无论 SUCCESS / PERMANENT）。 */
    public void countHandled() {
        handled.increment();
    }

    /** 从死信队列成功重投一次。 */
    public void countRetried() {
        retried.increment();
    }

    /** 放弃一次（坏消息、重试次数用尽、重投失败）。 */
    public void countDropped() {
        dropped.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(published.sum(), handled.sum(), retried.sum(), dropped.sum());
    }

    /** 计数快照，便于一行日志打全。 */
    public record Snapshot(long published, long handled, long retried, long dropped) {
    }
}
