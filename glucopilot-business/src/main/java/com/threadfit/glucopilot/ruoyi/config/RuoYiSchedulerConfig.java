package com.threadfit.glucopilot.ruoyi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Dedicated scheduler for whitelisted RuoYi jobs; it never evaluates user supplied code. */
@org.springframework.context.annotation.Profile("upstream-standalone")
@Configuration
public class RuoYiSchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler ruoyiTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ruoyi-job-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }
}
