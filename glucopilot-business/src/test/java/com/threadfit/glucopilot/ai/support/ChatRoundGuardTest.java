package com.threadfit.glucopilot.ai.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单飞保护的语义。
 * <p>
 * 这个类只有两件事要做对：**同一 key 不能同时进去两次**，以及**进去之后一定要能出来**
 * （出不来就等于这一轮永远被锁死，比不加保护更糟）。因此下面既有正常的获得/释放，
 * 也有"持有者死了怎么办"的过期接管。
 */
class ChatRoundGuardTest {

    private static final String KEY = "chat-round:1";

    private final ChatRoundGuard guard = new ChatRoundGuard();

    @Test
    void 首次获取应成功() {
        assertTrue(guard.tryAcquire(KEY));
        assertTrue(guard.inFlightCount() == 1);
    }

    @Test
    void 同一key在途时第二次获取应失败() {
        assertTrue(guard.tryAcquire(KEY));
        assertFalse(guard.tryAcquire(KEY), "同一条用户消息不能同时生成两轮");
    }

    @Test
    void 不同key互不影响() {
        assertTrue(guard.tryAcquire("chat-round:1"));
        assertTrue(guard.tryAcquire("chat-round:2"));
    }

    @Test
    void 释放后应能再次获取() {
        assertTrue(guard.tryAcquire(KEY));
        guard.release(KEY);
        assertTrue(guard.tryAcquire(KEY), "上一轮结束后必须允许重试，否则重试会被永久挡住");
    }

    @Test
    void 未持有时释放也应安全() {
        guard.release(KEY);
        guard.release(KEY);
        assertTrue(guard.inFlightCount() == 0);
    }

    @Test
    void 拿不到执行权时应返回空令牌() {
        assertNotNull(guard.tryAcquireToken(KEY));
        assertNull(guard.tryAcquireToken(KEY), "同一条用户消息不能同时生成两轮");
    }

    /**
     * 释放有两条路径（done 之前、doFinally 兜底）。第一次释放后下一轮可能立刻拿到同一个 key，
     * 兜底那次若不看令牌就删，会把<b>别人的</b>锁删掉，单飞保护静默失效。
     */
    @Test
    void 带令牌释放不能删掉下一轮刚拿到的锁() {
        Long first = guard.tryAcquireToken(KEY);
        assertNotNull(first);

        guard.release(KEY, first);
        Long second = guard.tryAcquireToken(KEY);
        assertNotNull(second, "上一轮释放后必须能立刻重来");

        // 兜底释放（还拿着上一轮的旧令牌）——不能把第二轮的执行权放掉
        guard.release(KEY, first);
        assertFalse(guard.tryAcquire(KEY), "旧令牌的兜底释放不应影响新一轮");

        guard.release(KEY, second);
        assertTrue(guard.tryAcquire(KEY));
    }

    @Test
    void 超过过期时长后应允许接管() throws InterruptedException {
        ChatRoundGuard shortLived = new ChatRoundGuard(Duration.ofMillis(60));
        assertTrue(shortLived.tryAcquire(KEY));
        assertFalse(shortLived.tryAcquire(KEY));

        Thread.sleep(120);

        // 持有者"死了"（没跑释放回调）：不能让这一轮被永久锁住，必须能自愈
        assertTrue(shortLived.tryAcquire(KEY), "过期后应允许接管，否则一次异常终止会永久卡住这一轮");
    }
}
