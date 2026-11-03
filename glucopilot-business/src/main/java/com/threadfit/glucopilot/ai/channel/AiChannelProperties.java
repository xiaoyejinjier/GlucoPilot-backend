package com.threadfit.glucopilot.ai.channel;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 通道级配置的统一视图（前缀 {@code ai.channels}）。
 *
 * <h2>它在解决什么问题</h2>
 * 文本与视觉曾是两套前缀、两种读取方式（{@code spring.ai.deepseek.*} 由 starter 读、
 * {@code ai.vision.*} 由本项目读），读取代码只能写两份几乎一样的
 * {@code yamlTextSpec()} / {@code yamlVisionSpec()}，且各自漏字段（文本漏 timeout、视觉漏 thinking）。
 * <p>
 * 这里的做法是<b>不改前缀、只加一层视图</b>：{@code ai.channels.text.*} /
 * {@code ai.channels.vision.*} 各自用占位符引用底层键，新代码只认这一份形状。
 * 协议统一（S3）之后两边的底层键都只剩本项目自己的 {@code ai.*}，这层视图仍保留——
 * 它的价值从「抹平两套前缀」变成「一处读取、一处兜底」。
 *
 * <h2>为什么 {@code thinking} 不在本类里</h2>
 * 两个协议拼写不同：DeepSeek 是 {@code thinking.type=enabled/disabled}，
 * OpenAI 是 {@code reasoning_effort}，且 OpenAI 兼容厂商（百炼）对后者并不都认。
 * 硬塞进同一字段只会制造「看起来配了、实际没发」。思维链仍由
 * {@code ai.scenes.<scene>.thinking}（按场景）与 provider options（按模型）表达。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.channels")
public class AiChannelProperties {

    /** 文本通道（DeepSeek 协议）。 */
    private Channel text = new Channel();

    /** 视觉通道（OpenAI 兼容协议）。 */
    private Channel vision = new Channel();

    public Channel channel(AiChannel channel) {
        return switch (channel) {
            case TEXT -> text;
            case VISION -> vision;
        };
    }

    /**
     * 单个通道的连接参数。
     * <p>
     * 字段集在两个通道间<b>刻意保持一致</b>：读配置的代码只有一份，
     * 任何一侧多出字段都会让「同一段代码读两个通道」重新退化成两个分支。
     */
    @Data
    public static class Channel {

        /** 端点根地址；留空由 provider SDK 用默认值。 */
        private String baseUrl;

        /** 凭据；只放 application-local.yaml 或环境变量，禁止入库。 */
        private String apiKey;

        /** 默认模型名（endpoint，不是业务编码）。 */
        private String model;

        /** 默认采样温度。 */
        private Double temperature;

        /** 默认输出上限。 */
        private Integer maxTokens;

        /**
         * 非流式调用超时（秒）。
         * <p>
         * 只作用于「一次请求一次响应」的调用（连通性测试、审核、摘要、记忆抽取）。
         * SSE 流式生成不设读超时——流本来就可能持续几十秒，按整段计时会误杀正常生成。
         */
        private int timeoutSeconds = 60;

        /**
         * 为什么没有「连接超时」这一项：
         * <p>
         * 协议统一到 OpenAI 兼容后，连接超时由 openai-java SDK 自己的
         * {@code OkHttpClient} 决定，{@link org.springframework.ai.openai.OpenAiChatOptions}
         * 只暴露整次调用的 {@code timeout(Duration)}，没有单独的 connect/read 拆分。
         * 此前的 {@code connectTimeoutSeconds} 只服务于已删除的 DeepSeek 分支
         * （{@code DeepSeekApi.Builder.restClientBuilder}），留着会让运维以为这里可调。
         */
        public Duration timeout() {
            return Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        }
    }
}
