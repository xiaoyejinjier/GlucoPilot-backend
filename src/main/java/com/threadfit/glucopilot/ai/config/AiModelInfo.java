package com.threadfit.glucopilot.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 运行时信息：当前生效的模型编码等，供业务落库留痕或回显使用。
 * 与 Spring AI 的 yml 配置保持单一来源，避免各处硬编码模型名。
 */
@Component
public class AiModelInfo {

    private final String modelCode;

    public AiModelInfo(@Value("${spring.ai.deepseek.chat.model:unknown}") String modelCode) {
        this.modelCode = modelCode;
    }

    /** 与 chat_session.model_code / chat_message.model_code 对齐的模型编码。 */
    public String modelCode() {
        return modelCode;
    }
}
