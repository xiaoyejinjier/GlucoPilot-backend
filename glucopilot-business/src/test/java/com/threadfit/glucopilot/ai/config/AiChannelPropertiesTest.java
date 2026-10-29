package com.threadfit.glucopilot.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code ai.channels.*} 的绑定契约：两个通道形状一致，缺省超时可用。
 * <p>
 * 这里刻意用 {@link MapConfigurationPropertySource} 而不是真实 application.yaml——
 * yaml 里那两个通道的值是占位符（{@code ${spring.ai.deepseek.chat.model:}}），
 * 不解析的话绑出来是字面量，测不出「同一段代码读两个通道」这件事本身。
 */
class AiChannelPropertiesTest {

    private static AiChannelProperties bind(Map<String, String> properties) {
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));
        return binder.bind("ai.channels", Bindable.of(AiChannelProperties.class))
                .orElseGet(AiChannelProperties::new);
    }

    @Test
    void 两个通道应能各自绑定且字段一一对应() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("ai.channels.text.base-url", "https://api.deepseek.com");
        properties.put("ai.channels.text.api-key", "sk-text");
        properties.put("ai.channels.text.model", "deepseek-v4-flash");
        properties.put("ai.channels.text.temperature", "0.7");
        properties.put("ai.channels.text.max-tokens", "2048");
        properties.put("ai.channels.vision.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.put("ai.channels.vision.api-key", "sk-vision");
        properties.put("ai.channels.vision.model", "qwen3-vl-plus");
        properties.put("ai.channels.vision.temperature", "0.2");
        properties.put("ai.channels.vision.max-tokens", "512");

        AiChannelProperties channels = bind(properties);

        assertEquals("deepseek-v4-flash", channels.getText().getModel());
        assertEquals("sk-text", channels.channel(AiChannel.TEXT).getApiKey());
        assertEquals(2048, channels.getText().getMaxTokens());
        assertEquals(0.7, channels.getText().getTemperature());
        assertEquals("qwen3-vl-plus", channels.getVision().getModel());
        assertEquals("sk-vision", channels.channel(AiChannel.VISION).getApiKey());
        assertEquals(512, channels.getVision().getMaxTokens());
        assertEquals(0.2, channels.getVision().getTemperature());
    }

    @Test
    void 未配置超时时应给出可直接使用的缺省值() {
        AiChannelProperties channels = bind(Map.of());

        assertEquals(Duration.ofSeconds(10), channels.getText().connectTimeout());
        assertEquals(Duration.ofSeconds(60), channels.getText().timeout());
        assertEquals(Duration.ofSeconds(10), channels.getVision().connectTimeout());
        assertEquals(Duration.ofSeconds(60), channels.getVision().timeout());
    }

    @Test
    void 零或负数超时不应绑成零秒() {
        AiChannelProperties channels = bind(Map.of(
                "ai.channels.text.timeout-seconds", "0",
                "ai.channels.text.connect-timeout-seconds", "-3"));

        // 0 秒会让 HttpClient.connectTimeout 抛异常，因此这里钳到 1 秒而不是原样使用
        assertEquals(Duration.ofSeconds(1), channels.getText().timeout());
        assertEquals(Duration.ofSeconds(1), channels.getText().connectTimeout());
    }

    @Test
    void channel方法应返回同一实例便于调用方按通道统一读写() {
        AiChannelProperties channels = new AiChannelProperties();

        assertSame(channels.getText(), channels.channel(AiChannel.TEXT));
        assertSame(channels.getVision(), channels.channel(AiChannel.VISION));
    }
}
