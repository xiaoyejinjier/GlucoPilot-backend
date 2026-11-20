package com.threadfit.glucopilot.ai.model.job;

import com.threadfit.glucopilot.ai.model.mapper.AiCallLogMapper;
import com.threadfit.glucopilot.ai.support.lock.AiConcurrencyPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 账单表的保留期清理（V75）。
 *
 * <h2>为什么账单也要有保留期</h2>
 * 它是纯观测数据，唯一的消费者是「最近一段时间的成本报表」。按 25 行/会话、日活 1000 会话估算
 * 约 2.5 万行/天，一年就是九百万行——留着没有商业价值，只有查询变慢与备份变大两个后果。
 *
 * <h2>为什么分批删而不是一条大 DELETE</h2>
 * 一次删几百万行会长时间持锁、撑大 undo、还可能把从库拖垮；账单是低价值数据，
 * 慢一点删没有任何代价。每批 {@code batchSize} 行，直到某个批次返回 0 行（说明删干净了）。
 *
 * <h2>为什么放在凌晨 3:30</h2>
 * 与社区审核节拍器、账号注销工人错开；这个点既没有用户聊天高峰，也不会和日终统计抢锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallLogCleanupJob {

    private final AiCallLogMapper mapper;

    /**
     * 多实例独占：{@code @Scheduled} 在每台机器上都会到点触发，没有闸门就会 N 台机器同时删同一批数据
     * （重复删除本身无害，但会把 {@code deleted} 计数打散、日志里看不出真实进度）。
     * 租约由 Redis 提供，抢不到就静默跳过——清理是幂等的，晚一天没有代价。
     */
    private final AiConcurrencyPolicy concurrency;

    /** 保留天数：默认 90 天（够做季度对比与"上个月花了多少"）。 */
    @Value("${ai.call-log.retention-days:90}")
    private int retentionDays;

    /** 单批删除行数。 */
    @Value("${ai.call-log.cleanup-batch-size:5000}")
    private int batchSize = 5000;

    @Scheduled(cron = "0 30 3 * * *")
    public void cleanup() {
        if (!concurrency.markJob("calllog-cleanup")) {
            log.debug("账单清理已被其他实例执行，本实例跳过");
            return;
        }
        try {
            runOnce(LocalDateTime.now());
        } catch (RuntimeException e) {
            // 定时任务抛异常会被 Spring 记一条 ERROR，但下一分钟不会重跑；这里吞掉并留日志，
            // 明天同一时间再来一次即可——清不掉的旧账单不影响任何功能
            log.warn("账单清理失败（下次调度再试）", e);
        }
    }

    /**
     * 执行一轮清理（包级可见，供单测按固定"现在"调用）。
     *
     * @return 总共删除的行数
     */
    int runOnce(LocalDateTime now) {
        if (retentionDays <= 0) {
            // 0/负数 = 关掉清理（本地调试想留着全部账单时用），不是"删光"
            return 0;
        }
        LocalDateTime before = now.minusDays(retentionDays);
        int batch = Math.max(batchSize, 1);
        int total = 0;
        while (true) {
            int deleted = mapper.deleteOlderThan(before, batch);
            total += deleted;
            if (deleted < batch) {
                break;
            }
        }
        if (total > 0) {
            log.info("账单清理完成：删除 {} 条（早于 {}）", total, before);
        }
        return total;
    }
}
