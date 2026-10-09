package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.memory.ChatMemoryReadAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆装配。
 * <p>
 * Spring AI 的 {@code ChatMemoryAutoConfiguration} 自带 {@code ChatMemoryRepository} 与
 * {@code ChatMemory} 两个 Bean（均为 {@code @ConditionalOnMissingBean}），默认用内存实现。
 * 本项目提供 {@link ChatMemoryRepository} 的 MySQL 实现（{@code MyBatisChatMemoryRepository}），
 * 框架随即改用我们的存储——<b>窗口裁剪语义仍由框架的 {@link MessageWindowChatMemory} 负责</b>，
 * 不再自己写 SQL 的 LIMIT 拼接。
 * <p>
 * 没有使用框架的 {@code MessageChatMemoryAdvisor}：它会写记忆，与本项目「助手消息在流结束后
 * 显式落库」的时序冲突（详见 {@link ChatMemoryReadAdvisor} 类注释）。
 */
@Configuration
public class AiChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AIProperties aiProperties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(aiProperties.getMemory().getWindowMessages())
                .build();
    }

    @Bean
    public ChatMemoryReadAdvisor chatMemoryReadAdvisor(ChatMemory chatMemory) {
        return new ChatMemoryReadAdvisor(chatMemory);
    }
}
