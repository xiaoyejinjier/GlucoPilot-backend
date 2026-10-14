package com.threadfit.glucopilot.ai.business.ocr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** OCR 独立线程池，避免视觉模型的慢调用占用 HTTP 工作线程。 */
@Configuration
public class OcrTaskConfig {

    @Bean(name = "ocrTaskExecutor")
    public Executor ocrTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ocr-task-");
        executor.initialize();
        return executor;
    }
}
