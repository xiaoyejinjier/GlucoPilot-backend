package com.threadfit.glucopilot.ai.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一轮生成的"单飞"保护：<b>同一条用户消息同时只允许一次生成</b>。
 * <p>
 * <b>它解决的是"重复扣费"，与数据库唯一约束分工不同：</b>
 * <ul>
 *   <li>{@code uk_session_client_active} + 助手消息的确定性 {@code client_msg_id}
 *       （见 {@code ChatMessageService}）是<b>正确性</b>保证——保证库里不会出现同一轮的<b>两条</b>回复，
 *       跨实例有效，但它只在 INSERT 时生效，而 INSERT 发生在模型调用<b>之后</b>；</li>
 *   <li>本类把拦截点前移到<b>调用模型之前</b>，因此重复的请求根本不会去调模型。
 *       实测背景：两个并发 /completions 各自调了一次模型（prompt_tokens 5855 + 5940），
 *       钱已经花掉，唯一约束只能事后把第二条回复挡回去。</li>
 * </ul>
 * <p>
 * <b>⚠️ 已知局限：这是单实例内的保护。</b>进程内的 {@link ConcurrentHashMap} 不跨实例，
 * 多实例部署时它只能把同一实例内的重复请求挡住，跨实例的重复仍要靠数据库那条约束兜底
 * （届时若要彻底消除重复扣费，需要把这里换成共享存储的 SETNX，例如项目里已有的
 * {@code common.idempotency.IdempotencyStore}，但它会往 AI 路径引入 Redis 依赖）。
 * 在当前"单实例部署"的前提下，用零依赖的方案覆盖掉绝大多数场景是划算的。
 * <p>
 * <b>为什么要做"过期接管"</b>：key 的释放挂在流终止回调上，正常结束/报错/客户端取消都会释放。
 * 但如果进程被 kill、或某个流的终止回调没能跑（例如设备断电），key 就会永久占位，
 * 表现为"这一轮永远说正在生成中"。因此过期时间远大于最长一轮（SSE 超时 60s）之后即可接管，
 * 让故障自愈而不是需要人工重启。
 */
@Slf4j
@Component
public class ChatRoundGuard {

    /** 超过这个时长仍未被释放，视为持有者已死亡，允许新请求接管（SSE 超时 60s，留足余量）。 */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /** key → 获得时刻（{@link System#nanoTime()}）。 */
    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();

    private final long staleAfterNanos;

    public ChatRoundGuard() {
        this(STALE_AFTER);
    }

    /** 供测试注入更短的过期时长（否则要等 5 分钟才能验证"过期接管"）。 */
    ChatRoundGuard(Duration staleAfter) {
        this.staleAfterNanos = staleAfter.toNanos();
    }

    /**
     * 尝试获得某一轮的执行权。
     *
     * @return {@code true} 表示拿到（调用方必须保证最终调用 {@link #release}）；
     *         {@code false} 表示已有同轮生成在进行中
     */
    public boolean tryAcquire(String key) {
        long now = System.nanoTime();
        Long previous = inFlight.putIfAbsent(key, now);
        if (previous == null) {
            return true;
        }
        if (now - previous < staleAfterNanos) {
            return false;
        }
        // 过期接管：CAS 替换，避免与"刚好在这一刻释放"或"另一个接管者"互相覆盖
        if (inFlight.replace(key, previous, now)) {
            // 静默接管会让"某轮卡住 5 分钟后自愈"这件事在日志里毫无痕迹：
            // 既看不出发生过，也无从判断是进程被 kill、还是流的终止回调漏了。这里显式留痕。
            log.warn("在途轮次已超过 {}s 未释放，判定持有者已死亡并接管：key={}（已持有 {}s）。"
                            + "若反复出现，检查流的终止回调是否总能执行（进程被 kill / 客户端异常断开）",
                    staleAfterNanos / 1_000_000_000L, key, (now - previous) / 1_000_000_000L);
            return true;
        }
        return false;
    }

    /** 释放执行权；未持有也安全（幂等）。 */
    public void release(String key) {
        inFlight.remove(key);
    }

    /** 当前在途轮次数，仅用于观测与测试。 */
    public int inFlightCount() {
        return inFlight.size();
    }
}
