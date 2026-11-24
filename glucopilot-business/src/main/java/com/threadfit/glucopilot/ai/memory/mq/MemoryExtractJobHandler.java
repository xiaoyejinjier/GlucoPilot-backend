package com.threadfit.glucopilot.ai.memory.mq;

import com.threadfit.glucopilot.ai.business.chat.mapper.ChatSessionMapper;
import com.threadfit.glucopilot.ai.memory.service.UserChatMemoryService;
import com.threadfit.glucopilot.common.mq.job.JobEnvelope;
import com.threadfit.glucopilot.common.mq.job.JobHandler;
import com.threadfit.glucopilot.common.mq.job.JobOutcome;
import com.threadfit.glucopilot.common.mq.job.JobTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 长期记忆抽取的任务处理器。
 *
 * <h2>认领（claim）为什么是"回库查一次"而不是加锁</h2>
 *
 * 记忆抽取的幂等性写在数据层：{@code chat_user_memory} 的唯一键是
 * {@code (user_id, memory_key, item_hash, is_deleted)}，同一句话重复抽只会刷新置信度。
 * 所以这里不需要分布式锁，只要在认领时确认"这个会话还欠一次抽取"——重复投递的消息到这一步就返回 false，
 * 不再触发模型调用（每次调用都是真实计费）。
 *
 * <h2>失败为什么一律 SUCCESS</h2>
 *
 * 记忆是增强项，{@code extractNow} 内部已经把异常吞成 0 并记日志。真出错时重投也没有意义
 * （窗口内容不会因为重投而变得可用），留给下一次会话结束或补扫重新触发即可。
 */
@Component
@RequiredArgsConstructor
public class MemoryExtractJobHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryExtractJobHandler.class);

    /** 没有指定原因时用它，日志里能区分"谁触发的这次抽取"。 */
    static final String DEFAULT_REASON = "session-ended";

    private final ChatSessionMapper sessionMapper;

    private final UserChatMemoryService memoryService;

    @Override
    public String type() {
        return JobTypes.MEMORY_EXTRACT;
    }

    @Override
    public boolean claim(JobEnvelope envelope) {
        Long sessionId = envelope.refId();
        if (sessionId == null) {
            return false;
        }
        return sessionMapper.countSessionNeedingMemory(sessionId) > 0;
    }

    @Override
    public JobOutcome handle(JobEnvelope envelope) {
        String reason = envelope.reason() == null || envelope.reason().isBlank()
                ? DEFAULT_REASON
                : envelope.reason();
        int written = memoryService.extractNow(envelope.userId(), envelope.refId(), reason);
        LOG.info("记忆抽取任务完成 sessionId={} reason={} 写入={}", envelope.refId(), reason, written);
        return JobOutcome.SUCCESS;
    }
}