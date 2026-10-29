package com.threadfit.glucopilot.ai.model.runtime;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 给非流式模型调用装超时。
 *
 * <h2>为什么需要它</h2>
 * DeepSeek 通道的 {@code DeepSeekChatOptions} 里<b>没有</b> timeout 字段
 * （连接参数在 {@code DeepSeekApi.Builder} 上，而它也只有 {@code restClientBuilder/webClientBuilder}，
 * 没有 {@code timeout()}）。于是后台填的 {@code timeout_ms} 对 DeepSeek 模型完全无效：
 * 模型不回就无限等，直到 Servlet 异步超时——现象是「偶发卡住两分钟」，不是报错。
 * <p>
 * 这里用 JDK 的 {@link HttpClient} 造请求工厂：Spring 7 的
 * {@link JdkClientHttpRequestFactory#setReadTimeout(Duration)} 接收 {@link Duration}，
 * 不需要再和 {@code int} 毫秒的旧 API 打交道（那些在 Spring 7 已废弃）。
 *
 * <h2>为什么只管非流式</h2>
 * SSE 流式生成本来就可能持续几十秒，用「整段响应」计时会误杀正常生成长文；
 * 真正的流式防线是客户端取消与 {@code spring.mvc.async.request-timeout}。
 * 本类服务的是「一次请求一次响应」的调用：连通性测试、社区审核、摘要、记忆抽取。
 */
public final class AiHttpTimeouts {

    private static final Duration DEFAULT_CONNECT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READ = Duration.ofSeconds(60);

    private AiHttpTimeouts() {
    }

    /** 造一个带连接超时与读超时的 {@link RestClient.Builder}。 */
    public static RestClient.Builder restClientBuilder(Duration connectTimeout, Duration readTimeout) {
        return RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout));
    }

    static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        Effective effective = effective(connectTimeout, readTimeout);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(effective.connect())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(effective.read());
        return factory;
    }

    /**
     * 归一化：null / 非正数一律回落到默认值。
     * <p>
     * 不能让 0 或负数漏下去——{@code HttpClient.connectTimeout(0)} 会抛
     * {@code IllegalArgumentException}，而这个异常会在<b>构造模型时</b>炸掉整个后台保存流程，
     * 报错信息还指向框架内部。
     */
    static Effective effective(Duration connectTimeout, Duration readTimeout) {
        return new Effective(
                positiveOr(connectTimeout, DEFAULT_CONNECT),
                positiveOr(readTimeout, DEFAULT_READ));
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    /** 归一化后的实际超时值（便于单测与日志）。 */
    record Effective(Duration connect, Duration read) {
    }
}
