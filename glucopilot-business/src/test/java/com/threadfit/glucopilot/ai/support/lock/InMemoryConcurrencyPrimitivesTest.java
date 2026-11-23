package com.threadfit.glucopilot.ai.support.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * 三个进程内原语的行为约定（同时是 {@code Redisson*} 实现的语义基准）：
 * 谁拿到名额、过期怎么算、计数何时归零、字段何时消失。
 *
 * <p>这些用例刻意不碰 Redis：它们保证"降级路径"是对的，而 Redis 实现与这里共用同一套语义。
 */
class InMemoryConcurrencyPrimitivesTest {

    @Test
    void 租约满员时应拒绝且释放后可再拿到() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();

        String first = guard.tryAcquire("k", 1, Duration.ofMinutes(1));
        String second = guard.tryAcquire("k", 1, Duration.ofMinutes(1));

        assertThat(first).isNotNull();
        assertThat(second).as("互斥语义：第二个必须拿不到").isNull();
        assertThat(guard.held("k")).isEqualTo(1);

        assertThat(guard.release("k", first)).isTrue();
        assertThat(guard.tryAcquire("k", 1, Duration.ofMinutes(1))).isNotNull();
    }

    @Test
    void 租约过期后应自动腾出名额() throws Exception {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        assertThat(guard.tryAcquire("k", 1, Duration.ofMillis(30))).isNotNull();

        Thread.sleep(80);

        assertThat(guard.held("k")).isZero();
        assertThat(guard.tryAcquire("k", 1, Duration.ofMinutes(1))).isNotNull();
    }

    @Test
    void 凭令牌释放不应误删别人的名额() {
        InMemoryLeaseGuard guard = new InMemoryLeaseGuard();
        String mine = guard.tryAcquire("k", 2, Duration.ofMinutes(1));
        String other = guard.tryAcquire("k", 2, Duration.ofMinutes(1));

        assertThat(guard.release("k", "not-a-real-token")).isFalse();
        assertThat(guard.release("k", mine)).isTrue();
        assertThat(guard.held("k")).isEqualTo(1);
        assertThat(guard.release("k", other)).isTrue();
        assertThat(guard.held("k")).isZero();
    }

    @Test
    void 限速器应在窗口内放行到上限然后拒绝() {
        InMemoryRateGate gate = new InMemoryRateGate();

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (gate.tryAcquire("r", 3, Duration.ofMinutes(1))) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void 限速器应在窗口过去后恢复() throws Exception {
        InMemoryRateGate gate = new InMemoryRateGate();
        assertThat(gate.tryAcquire("r", 1, Duration.ofMillis(30))).isTrue();
        assertThat(gate.tryAcquire("r", 1, Duration.ofMillis(30))).isFalse();

        Thread.sleep(80);

        assertThat(gate.tryAcquire("r", 1, Duration.ofMillis(30))).isTrue();
    }

    @Test
    void 缓存应支持首次写入成功与覆盖后再读() {
        InMemoryCacheStore store = new InMemoryCacheStore();

        assertThat(store.putIfAbsent("t", "f", "v1", Duration.ofMinutes(1))).isTrue();
        assertThat(store.putIfAbsent("t", "f", "v2", Duration.ofMinutes(1))).isFalse();
        assertThat(store.get("t", "f")).contains("v1");

        store.put("t", "f", "v3", Duration.ofMinutes(1));
        assertThat(store.get("t", "f")).contains("v3");
    }

    @Test
    void 缓存字段应在过期后消失() throws Exception {
        InMemoryCacheStore store = new InMemoryCacheStore();
        store.put("t", "f", "v", Duration.ofMillis(30));

        Thread.sleep(80);

        assertThat(store.get("t", "f")).isEmpty();
    }

    @Test
    void 按条件删除字段应只删匹配的() {
        InMemoryCacheStore store = new InMemoryCacheStore();
        store.put("t", "toolA\u00001", "a", Duration.ofMinutes(1));
        store.put("t", "toolA_extra\u00001", "b", Duration.ofMinutes(1));
        store.put("t", "toolB\u00001", "c", Duration.ofMinutes(1));

        store.deleteIf("t", field -> field.startsWith("toolA\u0000"));

        assertThat(store.get("t", "toolA\u00001")).isEmpty();
        assertThat(store.get("t", "toolA_extra\u00001")).isPresent();
        assertThat(store.get("t", "toolB\u00001")).isPresent();

        store.deleteAll("t");
        assertThat(store.get("t", "toolB\u00001")).isEmpty();
    }

    @Test
    void 计数器应在达到过期时刻后归零() throws Exception {
        InMemoryCounter counter = new InMemoryCounter();

        assertThat(counter.increment("c", 1, Instant.now().plusMillis(40))).isEqualTo(1);
        assertThat(counter.increment("c", 1, Instant.now().plusMillis(40))).isEqualTo(2);
        assertThat(counter.current("c")).isEqualTo(2);

        Thread.sleep(90);

        assertThat(counter.current("c")).isZero();
        assertThat(counter.increment("c", 1, Instant.now().plus(Duration.ofMinutes(1)))).isEqualTo(1);
    }
}
