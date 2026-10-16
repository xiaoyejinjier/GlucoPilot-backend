package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.memory.ChatContextReader;
import com.threadfit.glucopilot.ai.memory.ChatHistoryAssembler;
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
 * 框架随即改用我们的存储。
 * <p>
 * <b>⚠️ 窗口裁剪不在这里的 {@code maxMessages} 上（2026-10-12 修正）</b>：核对框架字节码后确认
 * {@code MessageWindowChatMemory.get()} 是<b>纯透传</b>、不做裁剪，裁剪只发生在 {@code add()} 路径上；
 * 而本项目从不走 {@code add()} 写记忆（{@code saveAll()} 主动抛异常，写入由业务 Service 显式完成）。
 * 也就是说 {@code maxMessages} 在当前实现下<b>不产生任何效果</b>——真正决定"送多少进 prompt"的是
 * {@link ChatMemoryReadAdvisor}（② 期起改为按 token 预算装配，见 {@code ChatHistoryAssembler}）。
 * 这里仍显式设置 {@code maxMessages}，是为了让"窗口语义"在读/写两侧都指向同一个配置项，
 * 避免将来一旦启用框架的写路径时窗口悄悄退化成框架默认值。
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
    public ChatMemoryReadAdvisor chatMemoryReadAdvisor(ChatMemory chatMemory,
                                                       ChatHistoryAssembler chatHistoryAssembler,
                                                       ChatContextReader chatContextReader) {
        // ② 期起窗口由装配器按 token 预算决定（读侧），与本类的 maxMessages（只影响写）是两回事
        return new ChatMemoryReadAdvisor(chatMemory, chatHistoryAssembler, chatContextReader);
    }
}
