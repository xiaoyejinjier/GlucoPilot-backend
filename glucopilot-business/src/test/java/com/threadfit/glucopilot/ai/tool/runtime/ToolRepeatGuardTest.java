package com.threadfit.glucopilot.ai.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

/**
 * {@link ToolRepeatGuard} 的语义：只拦「同会话 + 同工具 + 同入参」，其余一律放行。
 *
 * <p>这些用例盯的是<b>不该拦的别拦</b>：把「换了时间范围」的查询当成重复、或把两个会话串到一起，
 * 会让模型拿着旧答案回答新问题——比重复调用糟糕得多。
 */
class ToolRepeatGuardTest {

    private static final long SESSION = 11L;

    private static ToolRepeatGuard.Key key(long sessionId, String tool, String input) {
        return new ToolRepeatGuard.Key(sessionId, tool, input);
    }

    @Test
    void 同会话同工具同入参应命中上次结果() {
        ToolRepeatGuard guard = new ToolRepeatGuard();
        guard.remember(key(SESSION, "query_glucose_stats", "{\"days\":30}"), "{\"avg\":6.8}");

        Optional<String> recalled = guard.recall(key(SESSION, "query_glucose_stats", "{\"days\":30}"));

        assertThat(recalled).contains("{\"avg\":6.8}");
    }

    @Test
    void 入参不同不应命中() {
        ToolRepeatGuard guard = new ToolRepeatGuard();
        guard.remember(key(SESSION, "query_glucose_stats", "{\"days\":30}"), "{\"avg\":6.8}");

        assertThat(guard.recall(key(SESSION, "query_glucose_stats", "{\"days\":90}"))).isEmpty();
    }

    @Test
    void 不同会话不应互相命中() {
        ToolRepeatGuard guard = new ToolRepeatGuard();
        guard.remember(key(SESSION, "list_reminders", "{}"), "{\"alertCount\":2}");

        assertThat(guard.recall(key(SESSION + 1, "list_reminders", "{}"))).isEmpty();
    }

    @Test
    void 超过缓存时长应重新执行() throws Exception {
        ToolRepeatGuard guard = new ToolRepeatGuard(Duration.ofMillis(20));
        guard.remember(key(SESSION, "get_session_state", "{}"), "{\"msgCount\":3}");

        Thread.sleep(60);

        assertThat(guard.recall(key(SESSION, "get_session_state", "{}"))).isEmpty();
        // 过期条目被顺手删掉，不会一直占着内存
        assertThat(guard.entryCount(SESSION)).isZero();
    }

    @Test
    void 单会话条目数应有上限且淘汰的不是最新那条() {
        ToolRepeatGuard guard = new ToolRepeatGuard();
        // MAX_ENTRIES_PER_SESSION = 16，多写一条把最老的挤出去
        guard.remember(key(SESSION, "t", "0"), "r0");
        for (int i = 1; i <= ToolRepeatGuard.MAX_ENTRIES_PER_SESSION; i++) {
            guard.remember(key(SESSION, "t", String.valueOf(i)), "r" + i);
        }

        assertThat(guard.entryCount(SESSION)).isEqualTo(ToolRepeatGuard.MAX_ENTRIES_PER_SESSION);
        assertThat(guard.recall(key(SESSION, "t", "0"))).isEmpty();
        assertThat(guard.recall(key(SESSION, "t", String.valueOf(ToolRepeatGuard.MAX_ENTRIES_PER_SESSION))))
                .isPresent();
    }

    @Test
    void 过大的结果不应缓存() {
        ToolRepeatGuard guard = new ToolRepeatGuard();
        String huge = "x".repeat(ToolRepeatGuard.MAX_CACHEABLE_CHARS + 1);
        guard.remember(key(SESSION, "search_chat_history", "{\"keyword\":\"血糖\"}"), huge);

        assertThat(guard.recall(key(SESSION, "search_chat_history", "{\"keyword\":\"血糖\"}"))).isEmpty();
    }

    @Test
    void 会话数应有上限避免内存无限增长() {
        ToolRepeatGuard guard = new ToolRepeatGuard();
        for (int i = 0; i < ToolRepeatGuard.MAX_SESSIONS + 5; i++) {
            guard.remember(key(i, "t", "x"), "r" + i);
        }

        assertThat(guard.sessionCount()).isEqualTo(ToolRepeatGuard.MAX_SESSIONS);
    }
}
