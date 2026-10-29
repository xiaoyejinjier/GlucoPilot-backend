package com.threadfit.glucopilot.ai.model.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link AiHttpTimeouts} 的边界：null / 0 / 负数都要回落默认，显式值原样透传。
 * <p>
 * 0 与负数必须挡住：{@code HttpClient.connectTimeout(0)} 会抛 IllegalArgumentException，
 * 而这个异常会在构造模型时炸掉后台保存流程（后台表单允许填任意整数）。
 */
class AiHttpTimeoutsTest {

    @Test
    void 缺省值应为连接十秒读六十秒() {
        AiHttpTimeouts.Effective effective = AiHttpTimeouts.effective(null, null);

        assertEquals(Duration.ofSeconds(10), effective.connect());
        assertEquals(Duration.ofSeconds(60), effective.read());
    }

    @Test
    void 零和负数应回落默认而不是透传() {
        AiHttpTimeouts.Effective effective = AiHttpTimeouts.effective(Duration.ZERO, Duration.ofSeconds(-5));

        assertEquals(Duration.ofSeconds(10), effective.connect());
        assertEquals(Duration.ofSeconds(60), effective.read());
    }

    @Test
    void 显式值应原样使用() {
        AiHttpTimeouts.Effective effective = AiHttpTimeouts.effective(Duration.ofSeconds(3), Duration.ofSeconds(45));

        assertEquals(Duration.ofSeconds(3), effective.connect());
        assertEquals(Duration.ofSeconds(45), effective.read());
    }

    @Test
    void 应能造出带读超时的请求工厂与RestClient构造器() {
        JdkClientHttpRequestFactory factory = AiHttpTimeouts.requestFactory(
                Duration.ofSeconds(5), Duration.ofSeconds(30));
        RestClient.Builder builder = AiHttpTimeouts.restClientBuilder(
                Duration.ofSeconds(5), Duration.ofSeconds(30));

        // 读超时没有 getter（Spring 7 只提供 setter），这里只验证能构造出来且不抛
        assertNotNull(factory);
        assertNotNull(builder.build());
    }
}
