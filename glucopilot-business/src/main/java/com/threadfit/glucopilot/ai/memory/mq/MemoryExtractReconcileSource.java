package com.threadfit.glucopilot.ai.memory.mq;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
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
 * 记忆抽取的补扫来源：把「会话已结束、但记忆还没抽到位」的会话重新派一遍。
 *
 * <h2>为什么必须有它</h2>
 *
 * 抽取的触发点全在业务代码里（结束会话、惰性归档），一旦那一刻的消息发不出去（broker 重启、网络抖动、
 * 进程被 kill），这条会话就再也没人管——用户下次聊天时那些偏好与忌口就是"没记住"。
 * 审核漏了只是晚几秒审，记忆漏了是永久丢失，所以这里的兜底不是可选项。
 *
 * <h2>宽限为什么是 1 分钟</h2>
 *
 * 正常链路是「写库 → 事务提交后发消息 → 消费者立刻抽」，1 分钟足够跑完；比这更早出现的待办，
 * 要么是消息还在路上，要么是消费者正在跑（那时 {@code memory_extracted_upto_msg_id} 还没更新）。
 * 宽限太短会让补扫和消费者抢同一批任务，日志里出现两条"抽取完成"，白烧一次模型。
 */
@Component
@RequiredArgsConstructor
public class MemoryExtractReconcileSource implements JobReconcileSource {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryExtractReconcileSource.class);

    /** 宽限：刚结束的会话留给正常派发链路。 */
    static final int GRACE_MINUTES = 1;

    private final ChatSessionMapper sessionMapper;

    @Override
    public String name() {
        return "memory-extract";
    }

    @Override
    public List<JobEnvelope> pending(int limit) {
        LocalDateTime dbNow = sessionMapper.selectDbNow();
        if (dbNow == null) {
            // 连库都读不到时钟，说明数据库此刻不可用；这一轮直接跳过，别用进程时钟猜
            LOG.warn("补扫记忆抽取时读不到数据库时钟，跳过本轮");
            return List.of();
        }
        Map<String, Object> ignored = Map.of();
        List<Map<String, Object>> rows =
                sessionMapper.selectSessionsNeedingMemory(dbNow.minusMinutes(GRACE_MINUTES), limit);
        List<JobEnvelope> jobs = new ArrayList<>(rows == null ? 0 : rows.size() + 1);
        if (rows == null) {
            return jobs;
        }
        for (Map<String, Object> row : rows) {
            Long sessionId = number(row, "sessionId");
            if (sessionId == null) {
                continue;
            }
            jobs.add(JobEnvelope.of(
                    JobTypes.MEMORY_EXTRACT, sessionId, null, number(row, "userId"), "reconcile"));
        }
        return jobs;
    }

    /**
     * 取列值时同时认小写与全大写：MySQL 原样返回别名，H2 会把列名转成大写，
     * 单测与生产的列名大小写并不一致。
     */
    private static Long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        return value instanceof Number n ? n.longValue() : null;
    }
}