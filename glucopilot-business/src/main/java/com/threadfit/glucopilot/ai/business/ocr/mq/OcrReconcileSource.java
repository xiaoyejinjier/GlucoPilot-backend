package com.threadfit.glucopilot.ai.business.ocr.mq;

import com.threadfit.glucopilot.ai.business.ocr.mapper.OcrTaskMapper;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobReconcileSource;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * 血糖 OCR 的补扫来源，同时负责回收「卡在进行中」的任务。
 *
 * <h2>两类漏网之鱼</h2>
 *
 * ① {@code PENDING} 但没人处理：任务行写进了库、派发消息却丢了（broker 重启、进程在两次写之间被 kill）。
 * 用户界面上就是"一直显示识别中"。
 *
 * ② {@code RUNNING} 但进程已经不在了：识别到一半崩掉。这种状态没有任何人会推进它，
 * 而且它不满足 ① 的条件（状态不是 PENDING），所以必须单独回收。
 *
 * <h2>为什么回收只重试一次</h2>
 *
 * 视觉识别是整条链路最贵的一步。如果一张图会让进程崩溃，无限自动重试就是无限计费。
 * 第一次回收打回 {@code PENDING} 并留标记；第二次仍卡住就判 {@code FAILED} 并让用户重新上传。
 *
 * <h2>为什么放在补扫里而不是另起一个定时器</h2>
 *
 * 两者都是"处理没人管的异常状态"，共用同一个 5 分钟节拍；多一个 {@code @Scheduled} 只是多一份
 * 需要对齐的配置和一份需要单独测试的调度逻辑。
 */
@Component
@RequiredArgsConstructor
public class OcrReconcileSource implements JobReconcileSource {

    private static final Logger LOG = LoggerFactory.getLogger(OcrReconcileSource.class);

    /** 宽限：刚创建的任务留给正常派发链路，避免与消费者抢同一条。 */
    static final int GRACE_MINUTES = 1;

    /** 多旧算「卡住」：一次识别是秒级到十几秒，5 分钟还没动静只可能是进程没了。 */
    static final int STALE_MINUTES = 5;

    /** 借 {@code error_msg} 当"已经重试过一次"的标记（表上没有 attempt 列）。 */
    static final String RETRY_MARKER = "上次识别中断，正在自动重试";

    /** 重试过仍卡住时写给用户的结论。 */
    static final String FAIL_MESSAGE = "识别多次中断，请重新上传图片";

    private final OcrTaskMapper taskMapper;

    @Override
    public String name() {
        return "ocr-recognize";
    }

    @Override
    public List<JobEnvelope> pending(int limit) {
        LocalDateTime dbNow = taskMapper.selectDbNow();
        if (dbNow == null) {
            // 读不到数据库时钟说明库此刻不可用；不用进程时钟猜，直接跳过这一轮
            LOG.warn("补扫 OCR 任务时读不到数据库时钟，跳过本轮");
            return List.of();
        }
        LocalDateTime staleBefore = dbNow.minusMinutes(STALE_MINUTES);
        reclaimStale(staleBefore);

        List<Map<String, Object>> rows =
                taskMapper.selectPendingTasks(dbNow.minusMinutes(GRACE_MINUTES), limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<JobEnvelope> jobs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long taskId = number(row, "taskId");
            if (taskId == null) {
                continue;
            }
            jobs.add(JobEnvelope.of(
                    JobTypes.OCR_RECOGNIZE, taskId, null, number(row, "userId"), "reconcile"));
        }
        return jobs;
    }

    private void reclaimStale(LocalDateTime staleBefore) {
        int requeued = taskMapper.requeueStaleRunning(staleBefore, RETRY_MARKER);
        int failed = taskMapper.failStaleRunning(staleBefore, RETRY_MARKER, FAIL_MESSAGE);
        if (requeued > 0 || failed > 0) {
            LOG.warn("回收卡住的 OCR 任务：打回待识别 {} 条，判失败 {} 条（阈值 {}）",
                    requeued, failed, staleBefore);
        }
    }

    /**
     * 取列值时同时认小写与全大写：MySQL 原样返回别名，H2 会把别名转成大写，
     * 单测（H2）与生产（MySQL）的列名大小写并不一致。
     */
    private static Long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        return value instanceof Number n ? n.longValue() : null;
    }
}
