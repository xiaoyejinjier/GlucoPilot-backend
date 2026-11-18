package com.threadfit.glucopilot.ai.model.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缓存命中字段的一次性探针（临时观测，可随时删除）。
 * <p>
 * <b>它要回答的唯一问题</b>：我们跑「OpenAI 兼容协议 + DeepSeek 底座」，框架的
 * {@link Usage#getCacheReadInputTokens()} 到底是拿到了值，还是恒为 null？
 * <ul>
 *   <li>若为 null：说明 DeepSeek 顶层返回的 {@code prompt_cache_hit_tokens} 没人认领
 *       （OpenAI 原生是 {@code prompt_tokens_details.cached_tokens}），
 *       报表里的成本只能按全价算，需要另做解析；</li>
 *   <li>若有值：直接落库即可，不需要自己解析 native usage。</li>
 * </ul>
 * <p>
 * <b>为什么用 INFO 且只打一次</b>：线上默认只到 INFO，用 DEBUG 永远看不到；
 * 每个请求都打又会把日志淹掉。所以进程内第一次出现用量时打一条，之后静默——
 * 重启一次就能再验证一次，足够定案。
 * <p>
 * <b>为什么要跳过 prompt=0 的那一次</b>：实测工具回环里的第一个 chunk 会带一份
 * {@code prompt=0/completion=0} 的占位 usage。它既没有缓存字段、native 里也是空的，
 * 拿它当「第一条」会得出「框架不支持缓存」的错误结论——真实结论在带 token 的那次里。
 */
@Slf4j
public final class UsageProbe {

    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    private UsageProbe() {
    }

    public static void logOnce(Usage usage) {
        if (usage == null || !hasTokens(usage) || !LOGGED.compareAndSet(false, true)) {
            return;
        }
        try {
            Object nativeUsage = usage.getNativeUsage();
            log.info("用量探针（一次性）：prompt={} completion={} cacheRead={} cacheWrite={} nativeUsage={}{}",
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    cacheReadOf(usage),
                    cacheWriteOf(usage),
                    nativeUsage == null ? "null" : nativeUsage.getClass().getName(),
                    nativeUsage == null ? "" : " 内容=" + abbreviate(String.valueOf(nativeUsage)));
        } catch (RuntimeException e) {
            log.warn("用量探针执行失败（忽略）", e);
        }
    }

    private static boolean hasTokens(Usage usage) {
        try {
            Integer prompt = usage.getPromptTokens();
            return prompt != null && prompt > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Long cacheReadOf(Usage usage) {
        try {
            return usage.getCacheReadInputTokens();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long cacheWriteOf(Usage usage) {
        try {
            return usage.getCacheWriteInputTokens();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }
}
