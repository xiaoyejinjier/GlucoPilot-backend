package com.threadfit.glucopilot.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AIProperties {
    private PromptConfig prompts = new PromptConfig();

    @Data
    public static class PromptConfig {
        private AgentPromptConfig agentPrompts = new AgentPromptConfig();
        private RagPromptConfig ragPrompts = new RagPromptConfig();
    }

    @Data
    public static class AgentPromptConfig {
        private String agentSystemFile;
        private String memoryChatFile;
        private String toolCallSystemFile;
    }
    @Data
    public static class RagPromptConfig {

    }
}
