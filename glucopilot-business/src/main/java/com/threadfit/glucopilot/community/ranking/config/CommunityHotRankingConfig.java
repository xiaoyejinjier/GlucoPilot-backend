package com.threadfit.glucopilot.community.ranking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** 热榜调度与异步重建线程池；多实例靠 Redis 锁互斥。 */
@Configuration
@EnableScheduling
public class CommunityHotRankingConfig {

    @Bean(name = "hotRankingExecutor")
    public Executor hotRankingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("hot-rank-");
        // 队列满则丢弃：重建由定时任务兜底，不阻塞请求线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
