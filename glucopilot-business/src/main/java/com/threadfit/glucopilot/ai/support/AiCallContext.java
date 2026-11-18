package com.threadfit.glucopilot.ai.support;

import com.threadfit.glucopilot.ai.scene.SceneCode;
import reactor.util.context.ContextView;

import java.util.function.Supplier;

/**
 * 当前模型调用属于哪个场景、哪个用户（账单用）。
 *
 * <h2>为什么用 ThreadLocal 而不是改一串方法签名</h2>
 * 账单需要的三个值（场景/用户/会话）只有业务调用点知道，而真正落到
 * {@code DynamicChatModel.call/stream} 时已经穿过 ChatClient、Advisor 链两层框架代码——
 * 把参数一路透传下去要改 Spring AI 的接口，不可行；在每个 ChatClient 请求上挂 advice
 * 又要各自解析一遍。用调用点压栈、唯一漏斗出栈，是这里最省事且不侵入框架的做法。
 *
 * <h2>流式的坑（务必照做）</h2>
 * {@code DynamicChatModel.stream()} 必须在<b>方法入口</b>就把 {@link #current()} 取成局部变量：
 * Reactor 会在订阅与发射时换线程，ThreadLocal 不跟过去。捕获之后，{@code doOnNext/doOnError/
 * doFinally} 里的回调一律用那份快照。
 * （与 {@code ChatRoundGuard} 那次「doFinally 在 done 之后」是同一类教训：跨线程/跨时序的东西
 *   不能想当然地"当时读一次就行"。）
 *
 * <h2>嵌套与还原</h2>
 * {@code run} 保存旧值、结束时还原：业务里再包一层（例如 CHAT_IMAGE 里调视觉）不会把外层的场景冲掉。
 */
public final class AiCallContext {

    /** 一次调用归属：场景必填，用户/会话可空（系统调用）。 */
    public record Call(SceneCode scene, Long userId, Long sessionId) {

        public static Call of(SceneCode scene) {
            return new Call(scene, null, null);
        }
    }

    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    private AiCallContext() {
    }

    public static <T> T run(Call call, Supplier<T> body) {
        Call previous = CURRENT.get();
        CURRENT.set(call);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(Call call, Runnable body) {
        run(call, () -> {
            body.run();
            return null;
        });
    }

        /** 当前线程上的归属（同步调用用）；不在 {@link #run} 里时为 null（框架内部调用、启动期探活等）。 */
    public static Call current() {
        return CURRENT.get();
    }

    /**
     * 流式调用的归属键：放进 Reactor Context，随订阅穿过线程，不依赖 ThreadLocal。
     *
     * <p>为什么流式不能用 ThreadLocal：{@code ChatClient...stream()} 是惰性的，真正进
     * {@code DynamicChatModel.stream()} 要等订阅；订阅与发射都可能换线程，ThreadLocal 既跟不过去，
     * 也无法可靠清理——线城池里残留上一个请求的场景与用户，下一次调用就会被记到别人头上。</p>
     */
    public static final String CONTEXT_KEY = "ai.call.context";

    /** 从 Reactor Context 读归属；没挂过就是 null（框架内部调用、启动期探活）。 */
    public static Call fromContext(ContextView contextView) {
        if (contextView == null || !contextView.hasKey(CONTEXT_KEY)) {
            return null;
        }
        Object value = contextView.get(CONTEXT_KEY);
        return value instanceof Call call ? call : null;
    }
}
