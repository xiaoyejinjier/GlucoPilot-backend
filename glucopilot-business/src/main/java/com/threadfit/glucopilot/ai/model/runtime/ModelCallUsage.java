package com.threadfit.glucopilot.ai.model.runtime;

import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 一次模型调用的用量快照（prompt / completion / 缓存命中）。
 * <p>
 * <b>为什么要有这个类型</b>：用量原来散在三个地方各自取一遍——流式聚合（{@code ChatCompletionServiceImpl}）、
 * 摘要（{@code ChatSummaryServiceImpl} 的 {@code ChatAnswer}）、视觉（{@code VisionAnalyzer}），
 * 三处都只取 prompt/completion 两项。于是「缓存命中多少」这件事全仓没有任何一处读得到，
 * 报表也就只能按全价估算成本（见 {@code AI-上下文成本优化-执行计划.md} 的 F7/F8）。
 * 合成一个值对象后，取数规则只有一份，加字段也只改这一处。
 * <p>
 * <b>缓存字段为什么可能为 null</b>：Spring AI 2.0 的 {@link Usage} 有
 * {@code getCacheReadInputTokens()} / {@code getCacheWriteInputTokens()}，但只有实现了这两项的
 * 厂商客户端（Anthropic 那类）才会填；OpenAI 兼容通道把 {@code prompt_tokens_details.cached_tokens}
 * 塞在 native usage 里，DeepSeek 更是自创了 {@code prompt_cache_hit_tokens} / {@code prompt_cache_miss_tokens}。
 * 我们两种都试，取不到就是 <b>null</b>——绝不能落 0：0 在报表里会被当成「确实一次都没命中」，
 * 而 null 才是「不知道」。
 *
 * @param promptTokens     输入 token（厂商计费口径）
 * @param completionTokens 输出 token
 * @param cacheHitTokens   命中缓存的输入 token（读缓存），取不到为 null
 * @param cacheMissTokens  未被缓存命中的输入 token，由 prompt - hit 推得，取不到为 null
 */
public record ModelCallUsage(Integer promptTokens,
                             Integer completionTokens,
                             Long cacheHitTokens,
                             Long cacheMissTokens) {

    /** 视作「没有用量」的空快照（测试与降级路径用）。 */
    private static final ModelCallUsage EMPTY = new ModelCallUsage(null, null, null, null);

    public static ModelCallUsage empty() {
        return EMPTY;
    }

    /**
     * 从框架返回的用量元数据里取。
     * <p>
     * 全部字段都按「取不到就是 null」处理：任何一层解析异常都不该让一次已经成功的回复变成失败。
     */
    public static ModelCallUsage from(Usage usage) {
        if (usage == null) {
            return EMPTY;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        Long hit = cacheRead(usage);
        if (hit == null) {
            hit = cacheHitFromNative(usage.getNativeUsage());
        }
        Long miss = null;
        if (hit != null && prompt != null && hit <= prompt) {
            // 厂商给的是「命中多少」，没给「没命中多少」；减出来比再猜一个字段名可靠
            miss = prompt.longValue() - hit;
        }
        return new ModelCallUsage(prompt, completion, hit, miss);
    }

    /** 是否一项都没取到（落库时可以整体按 null 处理）。 */
    public boolean isEmpty() {
        return promptTokens == null && completionTokens == null && cacheHitTokens == null && cacheMissTokens == null;
    }

    /**
     * 逐字段合并：{@code newer} 里非 null 的项覆盖本对象，null 的项保留原值。
     * <p>
     * 流式聚合必须用它而不是直接赋值：同一次调用的多个 chunk 可能都带 usage
     * （有的是累计值、有的是空壳），直接覆盖会让「最后一个空壳」把已经取到的真实用量抹掉。
     */
    public ModelCallUsage merge(ModelCallUsage newer) {
        if (newer == null || newer.isEmpty()) {
            return this;
        }
        return new ModelCallUsage(
                newer.promptTokens != null ? newer.promptTokens : promptTokens,
                newer.completionTokens != null ? newer.completionTokens : completionTokens,
                newer.cacheHitTokens != null ? newer.cacheHitTokens : cacheHitTokens,
                newer.cacheMissTokens != null ? newer.cacheMissTokens : cacheMissTokens);
    }

    private static Long cacheRead(Usage usage) {
        try {
            // 默认实现返回 null，但第三方实现可能抛 UnsupportedOperationException
            return usage.getCacheReadInputTokens();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 从 native usage 里捞命中数：先看 OpenAI 兼容的 map 形态，再看 DeepSeek 自家 Usage 的
     * {@code cached_tokens}（它把 {@code PromptTokensDetails} 作为嵌套对象）。
     */
    private static Long cacheHitFromNative(Object nativeUsage) {
        if (nativeUsage == null) {
            return null;
        }
        try {
            if (nativeUsage instanceof Map<?, ?> map) {
                Long direct = asLong(map.get("prompt_cache_hit_tokens"));
                if (direct != null) {
                    return direct;
                }
                if (map.get("prompt_tokens_details") instanceof Map<?, ?> details) {
                    Long cached = asLong(details.get("cached_tokens"));
                    if (cached != null) {
                        return cached;
                    }
                }
                return asLong(map.get("cached_tokens"));
            }
            Method getter = nativeUsage.getClass().getMethod("getCachedTokens");
            return asLong(getter.invoke(nativeUsage));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 反射失败只意味着"这个厂商没按我们认得的形状给缓存数"，不是错误
            return null;
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
