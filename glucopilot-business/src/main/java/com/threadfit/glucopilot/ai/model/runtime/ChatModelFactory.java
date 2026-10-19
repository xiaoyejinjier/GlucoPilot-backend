package com.threadfit.glucopilot.ai.model.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * 按运行时规格构造 {@link ChatModel}。
 * <p>
 * <b>两条构造路径，坑都在注释里：</b>
 * <ul>
 *   <li><b>DeepSeek（providerCode=1）</b>：{@code DeepSeekApi} + {@code DeepSeekChatModel}。
 *       必须把 {@code toolCallingManager} 传进去，否则这个模型不能执行工具调用；</li>
 *   <li><b>OpenAI 兼容（其余）</b>：{@code OpenAiChatModel}，<b>把 baseUrl / apiKey / timeout 全部放进
 *       {@code OpenAiChatOptions}</b>，绝不手搓 {@code OpenAIClient}——只设同步 client 会让异步 client 为 null，
 *       {@code build()} 抛 {@code At least one credential source must be specified}，
 *       而报错指向框架内部，极易误判成"配置没读到"（本项目已踩过并记录在 {@code AiVisionClientConfig} 类注释）。</li>
 * </ul>
 * <p>
 * 本类只负责"构造"，不负责缓存与失效——那是 {@link DynamicChatModel} 的事。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final ToolCallingManager toolCallingManager;
    private final ObjectProvider<ObservationRegistry> observationRegistry;
    private final ObjectProvider<RetryTemplate> retryTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    /** 构造一个可用的 ChatModel。密钥缺失时直接抛异常（把"配置不可用"暴露在构造阶段，而不是发请求时）。 */
    public ChatModel build(AiModelSpec spec) {
        if (!StringUtils.hasText(spec.apiKey())) {
            throw new IllegalStateException("模型 " + spec.modelCode() + " 没有可用密钥"
                    + "（DB 未配置且 yaml/环境变量里也没有），请在后台上传密钥或配置环境变量");
        }
        ChatModel model = spec.deepSeek() ? buildDeepSeek(spec) : buildOpenAiCompatible(spec);
        // 只打非敏感字段；baseUrl 打出来是为了区分"连错端点"这类事故
        log.info("已按运行时配置构造模型 modelCode={} provider={} endpoint={} baseUrl={} source={}",
                spec.modelCode(), spec.providerCode(), spec.modelEndpoint(), spec.baseUrl(), spec.source());
        return model;
    }

    private ChatModel buildDeepSeek(AiModelSpec spec) {
        DeepSeekApi.Builder api = DeepSeekApi.builder().apiKey(spec.apiKey());
        if (StringUtils.hasText(spec.baseUrl())) {
            api.baseUrl(spec.baseUrl());
        }
        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder();
        if (StringUtils.hasText(spec.modelEndpoint())) {
            options.model(spec.modelEndpoint());
        }
        if (spec.temperature() != null) {
            options.temperature(spec.temperature());
        }
        if (spec.maxTokens() != null) {
            options.maxTokens(spec.maxTokens());
        }
        if (spec.topP() != null) {
            options.topP(spec.topP());
        }
        if (spec.thinking() != null) {
            if (spec.thinking()) {
                options.enableThinking();
            } else {
                options.disableThinking();
            }
        }

        DeepSeekChatModel.Builder builder = DeepSeekChatModel.builder()
                .deepSeekApi(api.build())
                .options(options.build())
                // 少了这一行，这个模型就不能调用工具（模型照常回答，只是永远不调工具，日志无异常）
                .toolCallingManager(toolCallingManager);
        observationRegistry.ifAvailable(builder::observationRegistry);
        retryTemplate.ifAvailable(builder::retryTemplate);
        return builder.build();
    }

    private ChatModel buildOpenAiCompatible(AiModelSpec spec) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey());
        if (StringUtils.hasText(spec.modelEndpoint())) {
            options.model(spec.modelEndpoint());
        }
        if (spec.temperature() != null) {
            options.temperature(spec.temperature());
        }
        if (spec.maxTokens() != null) {
            options.maxCompletionTokens(spec.maxTokens());
        }
        if (spec.topP() != null) {
            options.topP(spec.topP());
        }
        if (spec.timeout() != null) {
            options.timeout(spec.timeout());
        }

        OpenAiChatModel.Builder builder = OpenAiChatModel.builder().options(options.build());
        // 刻意不调用 builder.toolCallingManager(...)：
        //   1) 该重载在 Spring AI 2.0.1 已标记弃用（待删除）；
        //   2) 工具执行在 2.x 由 ChatClient 层的 ToolCallingAdvisor 负责，而本项目所有文本调用
        //      都走自动装配的 Builder（那里挂着该 Advisor），因此不依赖模型级 manager；
        //   3) 视觉通道是唯一使用 OpenAI 兼容协议的调用方，它按设计不应带工具。
        observationRegistry.ifAvailable(builder::observationRegistry);
        meterRegistry.ifAvailable(builder::meterRegistry);
        return builder.build();
    }

    /**
     * 连通性测试：用该配置发一次最小请求。
     * <p>
     * 这是后台"测试"按钮的实现。它的价值来自真实教训：视觉模型名写成账号下不存在的
     * {@code qwen3-vl-flash} 时，只能在用户发图后才看到 {@code 404 Model not exist}；
     * 有了这个方法，"配置对不对"在后台点一下就知道，并且能把错误归到具体类别。
     */
    public ConnectivityResult test(AiModelSpec spec, Duration timeout) {
        ChatModel model = build(spec);
        long start = System.nanoTime();
        try {
            var response = model.call(new org.springframework.ai.chat.prompt.Prompt(
                    "ping" + (timeout == null ? "" : "")));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String text = response == null || response.getResult() == null
                    ? "" : Optional.ofNullable(response.getResult().getOutput()).map(m -> m.getText()).orElse("");
            String modelEcho = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getModel();
            return ConnectivityResult.ok(elapsedMs, modelEcho, text == null ? "" : text.strip());
        } catch (RuntimeException e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return ConnectivityResult.fail(classify(e), elapsedMs, rootMessage(e));
        }
    }

    /**
     * 错误归类（启发式，但覆盖了实际会遇到的几类）。
     * <p>
     * 刻意按"管理员下一步该改哪个字段"来分类，而不是照搬异常类名：
     * 401 → 改密钥；404 → 改模型名；429 → 稍后重试或换额度；超时 → 改 baseUrl/超时。
     */
    static String classify(Throwable e) {
        String all = chainText(e).toLowerCase();
        if (all.contains("401") || all.contains("unauthorized") || all.contains("invalid api key")
                || all.contains("invalidaccesskeyid") || all.contains("authentication")) {
            return "AUTH";
        }
        if (all.contains("404") || all.contains("notfound") || all.contains("model not exist")
                || all.contains("model_not_found") || all.contains("does not exist")) {
            return "MODEL_NOT_FOUND";
        }
        if (all.contains("429") || all.contains("rate limit") || all.contains("ratelimit")
                || all.contains("quota")) {
            return "RATE_LIMIT";
        }
        if (all.contains("timeout") || all.contains("timed out")) {
            return "TIMEOUT";
        }
        if (all.contains("connect") || all.contains("unknownhost") || all.contains("unreachable")
                || all.contains("connection refused")) {
            return "NETWORK";
        }
        return "UNKNOWN";
    }

    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName()
                : message.substring(0, Math.min(400, message.length()));
    }

    private static String chainText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cursor = e;
        while (cursor != null) {
            sb.append(cursor.getClass().getName()).append(' ').append(cursor.getMessage()).append('\n');
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    /** 连通性测试结果。 */
    public record ConnectivityResult(boolean ok, String category, long elapsedMs,
                                     String modelEcho, String message) {

        static ConnectivityResult ok(long elapsedMs, String modelEcho, String message) {
            return new ConnectivityResult(true, "OK", elapsedMs, modelEcho, message);
        }

        static ConnectivityResult fail(String category, long elapsedMs, String message) {
            return new ConnectivityResult(false, category, elapsedMs, null, message);
        }

        /** 给管理员看的一句话建议。 */
        public String advice() {
            return switch (category) {
                case "OK" -> "连通正常";
                case "AUTH" -> "凭据无效：请检查密钥（或它属于别的账号/工作空间）";
                case "MODEL_NOT_FOUND" -> "模型名不存在：请核对本账号已开通的模型名";
                case "RATE_LIMIT" -> "被限流或额度不足：稍后重试或检查配额";
                case "TIMEOUT" -> "请求超时：检查 baseUrl 是否可达、或调大超时";
                case "NETWORK" -> "网络不可达：检查 baseUrl 与出网策略";
                default -> "未知错误：请看原始消息";
            };
        }
    }
}
