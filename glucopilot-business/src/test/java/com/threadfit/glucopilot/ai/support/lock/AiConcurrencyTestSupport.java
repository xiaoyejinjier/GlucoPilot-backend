package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;

import java.time.Clock;

/**
 * 测试用的并发原语装配：全部基于进程内实现，不需要 Redis。
 *
 * <p>为什么要有这个工具类：{@code ChatCompletionServiceImpl}、{@code ChatSummaryServiceImpl}
 * 这类被测类的构造器现在要一个 {@link AiConcurrencyPolicy}，如果每个测试各拼一遍
 * 六个参数，改一次阈值就要改十几个文件。集中在这里之后，测试只关心"给不给它一个能用的策略"。
 */
public final class AiConcurrencyTestSupport {

    private AiConcurrencyTestSupport() {
    }

    /** 默认策略（内部自带一套进程内实现）。 */
    public static AiConcurrencyPolicy policy() {
        return policy(new InMemoryLeaseGuard());
    }

    /** 复用外部传入的租约实现，便于测试断言"资源确实释放了"。 */
    public static AiConcurrencyPolicy policy(InMemoryLeaseGuard leaseGuard) {
        return policy(leaseGuard, relaxedProperties());
    }

    /** 关掉速率/配额的属性：多数单测只关心主流程，不关心闸门。 */
    public static AiConcurrencyProperties relaxedProperties() {
        AiConcurrencyProperties properties = new AiConcurrencyProperties();
        properties.setChatPerMinute(1_000_000);
        properties.setDailyChatTurns(1_000_000);
        properties.setStreamPermitsPerUser(1_000);
        properties.setClusterRoundPermits(1_000);
        return properties;
    }

    public static AiConcurrencyPolicy policy(InMemoryLeaseGuard leaseGuard, AiConcurrencyProperties properties) {
        return new AiConcurrencyPolicy(leaseGuard, new InMemoryRateGate(), new InMemoryCacheStore(),
                new InMemoryCounter(), properties, Clock.systemDefaultZone());
    }

    /** 关掉速率/配额限制的策略：多数单测只关心主流程，不关心闸门。 */
    public static AiConcurrencyPolicy unlimited() {
        return policy(new InMemoryLeaseGuard(), relaxedProperties());
    }
}
