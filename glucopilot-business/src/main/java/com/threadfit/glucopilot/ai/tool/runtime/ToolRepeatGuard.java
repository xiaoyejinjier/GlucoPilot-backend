package com.threadfit.glucopilot.ai.tool.runtime;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import com.threadfit.glucopilot.ai.support.lock.AiCacheStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 同一次会话里「同一个工具、同一份入参」的重复调用拦截。
 *
 * <p><b>为什么需要它</b>：模型的工具调用是逐轮决策的，它看不到「我上一轮已经查过这件事」——
 * 尤其当窗口滑走、结论只留在上下文里时，重复调用很容易发生。重复调用不只是浪费一次模型往返，
 * 还会在工具的记账表里留下两条一样的记录，让「这个用户今天查了几次血糖」这类统计失真。
 *
 * <p><b>判据刻意很窄</b>：会话 + 工具名 + <b>入参原文</b>三者都相同才算重复。
 * 入参不同（比如把 days 从 7 改成 30）一定放行——那本来就是新的问题。
 * 结果只缓 30 分钟：数据是会变的，今天下午和晚上问同一句「今天血糖怎么样」应该拿到不同的答案。
 *
 * <p><b>只做去重，不做限流</b>：调用次数上限仍由框架的 {@code spring.ai.tools.limits.*} 负责，
 * 这里若再写一套计数器，两处上限会互相打架（见 {@link AiToolRegistry} 的约定）。
 *
 * <h2>为什么从本地 LRU 换成 Redis 小缓存</h2>
 * 原来每个实例各存一份：用户在两台机器上聊同一个会话，第二台机器上的"刚查过"就丢了，
 * 模型会再查一遍；反过来，实例重启也会把缓存清空。现在落到共享缓存里，
 * 会话维度天然对齐，也不需要自己维护 LRU 与过期清理——TTL 交给 Redis。
 */
@Slf4j
@Component
public class ToolRepeatGuard {

    /** 结果缓存时长：超过就重新执行。 */
    static final Duration TTL = Duration.ofMinutes(30);

    /** 超过这个长度的结果不缓存：大结果复制一份内存不划算，重复执行也贵不到哪去。 */
    static final int MAX_CACHEABLE_CHARS = 8_000;

    /** 一张会话一张表：{@code ai:toolrepeat:{sessionId}}，字段是「工具名 + 入参」。 */
    private static final String KEY_PREFIX = "ai:toolrepeat:";

    private final AiCacheStore cacheStore;

    private final Duration ttl;

    /**
     * 退化形态：没有共享缓存时只在**本实例**内去重。
     *
     * <p>生产环境永远用容器注入的那个 Bean（带 Redis 小缓存）；这个构造器供单测与
     * "只想验证判据、不想搭 Redis"的场景使用——它的行为与改造前完全一致。
     */
    public ToolRepeatGuard() {
        this(new com.threadfit.glucopilot.ai.support.lock.InMemoryCacheStore(), TTL);
    }

    @Autowired
    public ToolRepeatGuard(AiCacheStore cacheStore, AiConcurrencyProperties properties) {
        this(cacheStore, Duration.ofMinutes(Math.max(properties.getToolCacheMinutes(), 1)));
    }

    ToolRepeatGuard(AiCacheStore cacheStore, Duration ttl) {
        this.cacheStore = cacheStore;
        this.ttl = ttl;
    }

    /** 去重键：会话 + 工具名 + 规范化后的入参。 */
    public record Key(long sessionId, String toolName, String input) {

        /** 会话内的字段名：会话 id 已经是表名的一部分，这里不再重复。 */
        String field() {
            return toolName + '\u0000' + input;
        }
    }

    /**
     * 命中上次结果则返回它；未命中或已过期返回空。
     *
     * @param key 去重键
     * @return 上次的结果（可能为空列表以外的任何字符串）
     */
    public Optional<String> recall(Key key) {
        return cacheStore.get(KEY_PREFIX + key.sessionId(), key.field());
    }

    /**
     * 记住这次结果，供同会话的后续调用复用。
     *
     * @param key 去重键
     * @param result 工具返回的原文
     */
    public void remember(Key key, String result) {
        if (result == null || result.length() > MAX_CACHEABLE_CHARS) {
            return;
        }
        cacheStore.put(KEY_PREFIX + key.sessionId(), key.field(), result, ttl);
    }

    /** 清空某会话的缓存（会话结束时调用；未接入也不影响正确性，只是多留一会儿缓存）。 */
    public void forget(long sessionId) {
        cacheStore.deleteAll(KEY_PREFIX + sessionId);
    }

    /**
     * 只清掉某会话里<b>某个工具</b>的缓存。
     *
     * <p>为什么需要比 {@link #forget(long)} 更细的粒度：缓存的前提是"同样的入参，底层数据没变"。
     * 写工具（记住一条长期记忆）恰好打破这个前提——用户刚说完"记住我不吃香菜"，紧接着问
     * "你还记得我忌口吗"，如果列表工具的结果还躺在缓存里，模型看到的就是写入之前的旧快照。
     * 清掉整个会话的缓存也能修好，但会连带把血糖、社区检索等无关结果一起作废，
     * 白白让下一次调用重新查库。所以按「工具名 + 入参」的键前缀精确删。
     *
     * @param sessionId 会话 id
     * @param toolName  工具名（与 {@link Key#toolName()} 同源，即 {@code @Tool(name=…)}）
     */
    public void forget(long sessionId, String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        String prefix = toolName + '\u0000';
        cacheStore.deleteIf(KEY_PREFIX + sessionId, field -> field.startsWith(prefix));
    }
}
