package com.threadfit.glucopilot.ai.memory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import com.threadfit.glucopilot.ai.memory.service.ChatSummaryService;

/**
 * 摘要任务线程池（② 期）。
 * <p>
 * 与 OCR 用一个独立池是同一套理由：<b>不要用 HTTP 工作线程去等外部模型</b>。
 * 这里额外把池做得更小（2 线程）、队列更长：摘要是"可以晚一点、可以被丢弃"的增强任务，
 * 它绝不能和"用户正在等的那一轮问答"抢线程。
 * <p>
 * 队列满了会走 {@code AbortPolicy}（默认）抛异常，由 {@link ChatSummaryService} 捕获并记日志——
 * 也就是"丢一次摘要"，下一轮还会再触发。这是刻意的：宁可丢摘要，也不要让摘要拖慢对话。
 */
@Configuration
public class ChatSummaryConfig {

    @Bean(name = "chatSummaryExecutor")
    public Executor chatSummaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-summary-");
        executor.initialize();
        return executor;
    }
}
