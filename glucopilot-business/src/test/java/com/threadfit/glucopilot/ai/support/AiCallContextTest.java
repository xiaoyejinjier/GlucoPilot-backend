package com.threadfit.glucopilot.ai.support;

import com.threadfit.glucopilot.ai.scene.SceneCode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账单上下文（ThreadLocal）的压入与清理。
 *
 * <h2>为什么必须钉"清理"这条</h2>
 * 线程池里的线程是复用的：一次调用若没在 {@code finally} 里清掉上下文，下一个跑在同一线程上的
 * 请求就会把账单记到上一个用户头上——错账不会报错，只会让报表悄悄错位。
 */
class AiCallContextTest {

    @Test
    void 没有压入上下文时应为空() {
        assertThat(AiCallContext.current()).isNull();
    }

    @Test
    void 运行期间可读到上下文且结束后必须清空() {
        AiCallContext.Call call = new AiCallContext.Call(SceneCode.CHAT, 27L, 178L);

        String scene = AiCallContext.run(call, () -> {
            AiCallContext.Call current = AiCallContext.current();
            assertThat(current).isNotNull();
            assertThat(current.userId()).isEqualTo(27L);
            assertThat(current.sessionId()).isEqualTo(178L);
            return current.scene().name();
        });

        assertThat(scene).isEqualTo("CHAT");
        assertThat(AiCallContext.current()).isNull();
    }

    @Test
    void 嵌套运行结束后应恢复外层上下文() {
        AiCallContext.Call outer = new AiCallContext.Call(SceneCode.CHAT, 1L, 2L);
        AiCallContext.Call inner = new AiCallContext.Call(SceneCode.SUMMARY, 1L, 2L);

        AiCallContext.run(outer, () -> {
            AiCallContext.run(inner, () -> {
                assertThat(AiCallContext.current().scene()).isEqualTo(SceneCode.SUMMARY);
                return null;
            });
            // 内层退出后必须回到外层，否则外层剩下的记账会挂到内层场景上
            assertThat(AiCallContext.current().scene()).isEqualTo(SceneCode.CHAT);
            return null;
        });
    }

    @Test
    void 异常路径也必须清空上下文() {
        AtomicReference<AiCallContext.Call> leaked = new AtomicReference<>();

        assertThatThrownBy(() -> AiCallContext.run(new AiCallContext.Call(SceneCode.MEMORY, 5L, 6L),
                () -> {
                    leaked.set(AiCallContext.current());
                    throw new IllegalStateException("模型炸了");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(leaked.get()).isNotNull();
        assertThat(AiCallContext.current()).isNull();
    }

    @Test
    void 只给场景的便捷方法应把用户与会话留空() {
        AiCallContext.Call call = AiCallContext.run(AiCallContext.Call.of(SceneCode.MODERATION),
                AiCallContext::current);

        assertThat(call.scene()).isEqualTo(SceneCode.MODERATION);
        assertThat(call.userId()).isNull();
        assertThat(call.sessionId()).isNull();
    }

    @Test
    void 流式归属应从Reactor上下文读到而不是ThreadLocal() {
        AiCallContext.Call call = new AiCallContext.Call(SceneCode.CHAT, 27L, 178L);
        AtomicReference<AiCallContext.Call> seenAtSubscribe = new AtomicReference<>();

        // 模拟 DynamicChatModel.stream()：在订阅时从 ContextView 读，而不是 current()
        Flux<String> flux = Flux.deferContextual(contextView -> {
                    seenAtSubscribe.set(AiCallContext.fromContext(contextView));
                    return Flux.just("a", "b");
                })
                .contextWrite(context -> context.put(AiCallContext.CONTEXT_KEY, call));

        assertThat(flux.collectList().block()).containsExactly("a", "b");
        assertThat(seenAtSubscribe.get()).isNotNull();
        assertThat(seenAtSubscribe.get().scene()).isEqualTo(SceneCode.CHAT);
        assertThat(seenAtSubscribe.get().userId()).isEqualTo(27L);
        // ThreadLocal 从未被写过：不会在线程池里残留
        assertThat(AiCallContext.current()).isNull();
    }

    @Test
    void 没挂上下文时从Reactor上下文读到null() {
        AtomicReference<AiCallContext.Call> seen = new AtomicReference<>();
        Flux.deferContextual(contextView -> {
            seen.set(AiCallContext.fromContext(contextView));
            return Flux.just("x");
        }).collectList().block();

        assertThat(seen.get()).isNull();
        assertThat(AiCallContext.fromContext(null)).isNull();
    }
}
