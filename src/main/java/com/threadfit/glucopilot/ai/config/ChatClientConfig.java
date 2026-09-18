package com.threadfit.glucopilot.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 单例：默认系统提示词由 {@link SystemPromptProvider} 启动时加载。
 * <p>
 * 注意：记忆 / 审计 / 计量等 advisor 留到 L2 按需挂载；这里不写无参 {@code defaultAdvisors()}——
 * 它等价于什么都没做，只会误导后来人以为已经挂上了。
 */
@Configuration
@RequiredArgsConstructor
public class ChatClientConfig {

    private final SystemPromptProvider systemPromptProvider;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(systemPromptProvider.text())
                .build();
    }
}
