package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.config.properties.AIProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 启动期记忆配置自洽性校验。
 *
 * <p>这三条关系都属于「配错了不报错、只表现为模型健忘或账单变贵」，所以校验本身要钉死：
 * 哪一条越界就返回哪一条的建议，全部自洽时返回 null（调用方据此决定要不要打 WARN）。
 */
class AiMemoryConfigCheckTest {

    private final AIProperties properties = new AIProperties();

    @Test
    void 配置自洽时不应报告问题() {
        AIProperties.MemoryProperties memory = properties.getMemory();
        memory.setWindowMessages(20);
        memory.setKeepRecentMessages(6);
        memory.setSummaryMinPendingMessages(12);
        memory.setMaxPromptTokens(16000);

        assertThat(AiMemoryConfigCheck.violation(memory)).isNull();
        // @PostConstruct 里也只是 WARN，绝不能因为配置可疑就把服务拦下来
        assertThatCode(() -> new AiMemoryConfigCheck(properties).check()).doesNotThrowAnyException();
    }

    @Test
    void 窗口装不下未摘要消息时应给出推荐值() {
        AIProperties.MemoryProperties memory = properties.getMemory();
        memory.setWindowMessages(10);
        memory.setKeepRecentMessages(6);
        memory.setSummaryMinPendingMessages(12);
        memory.setMaxPromptTokens(16000);

        String violation = AiMemoryConfigCheck.violation(memory);

        assertThat(violation).isNotNull();
        assertThat(violation).contains("window-messages(10)");
        // 6 + 12 + 2 = 20
        assertThat(violation).contains("20");
    }

    @Test
    void 保底条数占满窗口时应直接报错() {
        AIProperties.MemoryProperties memory = properties.getMemory();
        memory.setWindowMessages(6);
        memory.setKeepRecentMessages(6);
        memory.setSummaryMinPendingMessages(0);
        memory.setMaxPromptTokens(16000);

        String violation = AiMemoryConfigCheck.violation(memory);

        assertThat(violation).isNotNull();
        assertThat(violation).contains("keep-recent-messages(6)");
    }

    @Test
    void 预算小于固定开销时应提示() {
        AIProperties.MemoryProperties memory = properties.getMemory();
        memory.setWindowMessages(20);
        memory.setKeepRecentMessages(6);
        memory.setSummaryMinPendingMessages(12);
        memory.setMaxPromptTokens(4096);

        String violation = AiMemoryConfigCheck.violation(memory);

        assertThat(violation).isNotNull();
        assertThat(violation).contains("max-prompt-tokens(4096)");
    }
}
