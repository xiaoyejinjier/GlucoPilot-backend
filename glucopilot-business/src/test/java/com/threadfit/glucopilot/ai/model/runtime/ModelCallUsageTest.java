package com.threadfit.glucopilot.ai.model.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量快照的取数规则。
 * <p>
 * 这里钉的是「取不到就是 null，绝不能是 0」这条底线：0 在报表里等价于
 * 「确实一次都没命中」，会把「没解析出来」伪装成「缓存没用上」，比没有数据更糟。
 */
class ModelCallUsageTest {

    @Test
    void 框架给出缓存命中时应推出未命中数() {
        ModelCallUsage usage = ModelCallUsage.from(new FakeUsage(1000, 200, 800L, null, null));

        assertThat(usage.promptTokens()).isEqualTo(1000);
        assertThat(usage.completionTokens()).isEqualTo(200);
        assertThat(usage.cacheHitTokens()).isEqualTo(800L);
        assertThat(usage.cacheMissTokens()).isEqualTo(200L);
    }

    @Test
    void 框架没给命中时应从DeepSeek的native字段取() {
        Map<String, Object> nativeUsage = new LinkedHashMap<>();
        nativeUsage.put("prompt_cache_hit_tokens", 700);
        nativeUsage.put("prompt_cache_miss_tokens", 300);

        ModelCallUsage usage = ModelCallUsage.from(new FakeUsage(1000, 50, null, null, nativeUsage));

        assertThat(usage.cacheHitTokens()).isEqualTo(700L);
        assertThat(usage.cacheMissTokens()).isEqualTo(300L);
    }

    @Test
    void OpenAI风格的嵌套命中字段也应认得() {
        Map<String, Object> nativeUsage = new LinkedHashMap<>();
        nativeUsage.put("prompt_tokens_details", Map.of("cached_tokens", 512));

        ModelCallUsage usage = ModelCallUsage.from(new FakeUsage(2048, 64, null, null, nativeUsage));

        assertThat(usage.cacheHitTokens()).isEqualTo(512L);
        assertThat(usage.cacheMissTokens()).isEqualTo(1536L);
    }

    @Test
    void 完全取不到缓存数时应为null而不是0() {
        ModelCallUsage usage = ModelCallUsage.from(new FakeUsage(1000, 20, null, null, null));

        assertThat(usage.cacheHitTokens()).isNull();
        assertThat(usage.cacheMissTokens()).isNull();
        // 但 prompt/completion 照常可用——不能因为缓存字段缺失就丢掉主用量
        assertThat(usage.promptTokens()).isEqualTo(1000);
    }

    @Test
    void 命中数超过prompt时不应推出负数() {
        ModelCallUsage usage = ModelCallUsage.from(new FakeUsage(100, 10, 500L, null, null));

        assertThat(usage.cacheHitTokens()).isEqualTo(500L);
        assertThat(usage.cacheMissTokens()).isNull();
    }

    @Test
    void 没有用量时应返回空快照() {
        assertThat(ModelCallUsage.from(null).isEmpty()).isTrue();
        assertThat(ModelCallUsage.empty().isEmpty()).isTrue();
    }

    @Test
    void 合并时非空字段保留且空值不覆盖已有值() {
        ModelCallUsage partial = new ModelCallUsage(1000, null, null, null);

        ModelCallUsage merged = partial
                .merge(ModelCallUsage.empty())
                .merge(new ModelCallUsage(null, 42, 800L, null));

        assertThat(merged.promptTokens()).isEqualTo(1000);
        assertThat(merged.completionTokens()).isEqualTo(42);
        assertThat(merged.cacheHitTokens()).isEqualTo(800L);
    }

    /** 可控的 Usage 实现：Spring AI 的 DefaultUsage 不带缓存字段，测不了这条路径。 */
    private record FakeUsage(Integer promptTokens,
                             Integer completionTokens,
                             Long cacheRead,
                             Long cacheWrite,
                             Object nativeUsage) implements Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return nativeUsage;
        }

        @Override
        public Long getCacheReadInputTokens() {
            return cacheRead;
        }

        @Override
        public Long getCacheWriteInputTokens() {
            return cacheWrite;
        }
    }
}
