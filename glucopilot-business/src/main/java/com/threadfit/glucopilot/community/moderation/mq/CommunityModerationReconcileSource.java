package com.threadfit.glucopilot.community.moderation.mq;

import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobReconcileSource;
import com.threadfit.glucopilot.common.mq.job.JobTypes;
import com.threadfit.glucopilot.community.moderation.mapper.CommunityModerationMapper;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审核域的<b>补扫源</b>：把"库里还在待审、但消息可能没发出去"的内容再派一遍。
 *
 * <h2>为什么这条后路必须存在</h2>
 *
 * 消息与会话的写库不在同一个事务里，所以任何时刻都可能有三种漏网：事务提交后投递失败（只 WARN）、
 * RabbitMQ 重启丢消息、有人直接改库把状态改回待审。补扫用数据库做真相，把这三类缺口一并补上。
 *
 * <h2>为什么都带"超过一分钟"</h2>
 *
 * 刚提交的内容正常会在几百毫秒内被消费掉；补扫如果不等这一分钟，就会和正常路径重复派发同一批内容，
 * 队列里出现大量"抢不到（已被人审）"的空消息。
 */
@Component
@RequiredArgsConstructor
public class CommunityModerationReconcileSource implements JobReconcileSource {

    /** 待审多久没动静才算"可能漏了"。 */
    private static final int GRACE_MINUTES = 1;

    private final CommunityModerationMapper mapper;

    @Override
    public String name() {
        return "community-moderation";
    }

    @Override
    public List<JobEnvelope> pending(int limit) {
        // 宽限窗口必须用数据库时钟：created_at/updated_at 是 CURRENT_TIMESTAMP 写的，与进程时区可能差 8 小时，
        // 用 JVM 时间会把刚提交的内容当成"老任务"立刻补投一遍
        LocalDateTime dbNow = mapper.selectDbNow();
        LocalDateTime before = (dbNow != null ? dbNow : LocalDateTime.now()).minusMinutes(GRACE_MINUTES);
        List<JobEnvelope> jobs = new ArrayList<>();
        for (Map<String, Object> row : mapper.pendingArticleJobs(before, limit)) {
            jobs.add(
                    JobEnvelope.of(
                            JobTypes.MODERATION_ARTICLE,
                            number(row, "news_id"),
                            number(row, "revision") == null ? null : number(row, "revision").intValue(),
                            null,
                            "reconcile"));
        }
        for (Map<String, Object> row : mapper.pendingCommentJobs(before, limit)) {
            jobs.add(
                    JobEnvelope.of(
                            JobTypes.MODERATION_COMMENT,
                            number(row, "id"),
                            null,
                            number(row, "user_id"),
                            "reconcile"));
        }
        return jobs;
    }

    /**
     * 取列值：MySQL 原样返回列名，H2（单测）会把未加引号的列名转成大写，因此两种写法都要认。
     */
    private static Long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase(java.util.Locale.ROOT));
        }
        return value instanceof Number n ? n.longValue() : null;
    }
}
