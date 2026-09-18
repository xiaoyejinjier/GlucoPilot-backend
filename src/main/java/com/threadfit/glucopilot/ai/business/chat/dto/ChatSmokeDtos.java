package com.threadfit.glucopilot.ai.business.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * L0 冒烟接口的出入参。正式对话接口见接口文档 2.23 / 2.24 / 2.25。
 */
public final class ChatSmokeDtos {

    private ChatSmokeDtos() {
    }

    @Data
    public static class SmokeRequest {

        @NotBlank(message = "message 不能为空")
        @Size(max = 500, message = "message 长度不能超过 500")
        private String message;
    }

    @Data
    @Builder
    public static class SmokeResponse {

        /** 模型回复正文 */
        private String reply;
        /** 实际使用的模型名（来自响应元数据，用于确认 yaml 配置真的生效） */
        private String model;
        /** 端到端耗时（毫秒） */
        private Long latencyMs;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        /** 结束原因，如 stop / length / content_filter */
        private String finishReason;
        /** 系统提示词来源文件 */
        private String systemPromptSource;
        /** 系统提示词字数（>0 才说明提示词真的加载了） */
        private Integer systemPromptChars;
    }
}
