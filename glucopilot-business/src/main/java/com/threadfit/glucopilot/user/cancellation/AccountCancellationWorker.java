package com.threadfit.glucopilot.user.cancellation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationMode;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号注销清理 worker。
 *
 * <h2>职责只有三件事</h2>
 * <ol>
 *   <li>扫出"该执行"的凭据：{@code status=PENDING} 且（立即模式，或恢复期已到期）；</li>
 *   <li>用 CAS 把 PENDING 抢成 RUNNING——这是多实例并发下唯一的互斥手段；</li>
 *   <li>调 {@link AccountDataPurger}，然后按结果记 EXECUTED 或失败重试。</li>
 * </ol>
 *
 * <h2>失败为什么不回滚成 NORMAL</h2>
 * 一旦开始物理删除，把 status 改回 NORMAL 会造出"一半数据没了、账号却还能用"的状态，
 * 比停在注销中危险得多。因此失败只累加 {@code fail_count}：3 次以内回到 PENDING 重试，
 * 达到 3 次置 FAILED(4)，保留人工介入的入口，<b>账号状态不回退</b>。
 *
 * <h2>为什么每步独立事务</h2>
 * 见 {@link AccountDataPurger} 的类注释：重试只补没做完的步骤，不全量回滚。
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "account.cancellation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AccountCancellationWorker {

    private static final Logger log = LoggerFactory.getLogger(AccountCancellationWorker.class);

    /** 与方案一致：3 次仍失败就置 FAILED，等人工排查。 */
    private static final int MAX_FAILURES = 3;
    /** 单轮最多处理多少条：避免一次拉太多把数据库压住。 */
    private static final int BATCH_SIZE = 20;
    /** last_error 列是 VARCHAR(512)，留点余量防止截断报错。 */
    private static final int LAST_ERROR_MAX = 480;

    private final UserAccountCancellationMapper cancellationMapper;
    private final AccountDataPurger purger;

    /** 定时轮询入口。 */
    @Scheduled(
            initialDelayString = "${account.cancellation.initial-delay:20000}",
            fixedDelayString = "${account.cancellation.poll-delay:30000}")
    public void poll() {
        List<UserAccountCancellation> due = cancellationMapper.selectList(
                new LambdaQueryWrapper<UserAccountCancellation>()
                        .eq(UserAccountCancellation::getStatus, CancellationStatus.PENDING.getCode())
                        .and(w -> w
                                .eq(UserAccountCancellation::getMode, CancellationMode.IMMEDIATE.getCode())
                                .or()
                                .le(UserAccountCancellation::getIsolateUntil, LocalDateTime.now()))
                        .orderByAsc(UserAccountCancellation::getId)
                        .last("LIMIT " + BATCH_SIZE));
        for (UserAccountCancellation record : due) {
            process(record);
        }
    }

    /**
     * 处理单条凭据（public 是为了让单测能直接驱动，不必等定时器）。
     *
     * <p>抢不到执行权就安静返回：另一个实例已经在处理同一条，重复执行没有意义。
     */
    public void process(UserAccountCancellation record) {
        Long id = record.getId();
        if (cancellationMapper.claim(id) != 1) {
            log.debug("注销凭据已被其他实例抢占，跳过 id={}", id);
            return;
        }
        try {
            purger.purge(record.getUserId());
            cancellationMapper.markExecuted(id);
            log.info("账号注销执行完成 id={} userId={}", id, record.getUserId());
        } catch (RuntimeException e) {
            // 注意：这里不重抛。定时任务里抛出去只会被 Spring 的调度器吞掉并打断后续条目，
            // 失败必须落到凭据上（fail_count / last_error），才能被重试或人工看到。
            int failures = (record.getFailCount() == null ? 0 : record.getFailCount()) + 1;
            int nextStatus = failures >= MAX_FAILURES
                    ? CancellationStatus.FAILED.getCode()
                    : CancellationStatus.PENDING.getCode();
            cancellationMapper.markFailed(id, nextStatus, truncate(e));
            log.error("账号注销执行失败 id={} userId={} failures={} nextStatus={}",
                    id, record.getUserId(), failures, nextStatus, e);
        }
    }

    /** 只保留异常类型与消息，不记录可能含健康内容的堆栈细节。 */
    private static String truncate(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() <= LAST_ERROR_MAX ? text : text.substring(0, LAST_ERROR_MAX);
    }
}
