package com.threadfit.glucopilot.ai.config;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import com.threadfit.glucopilot.ai.support.lock.AiCacheStore;
import com.threadfit.glucopilot.ai.support.lock.AiCounter;
import com.threadfit.glucopilot.ai.support.lock.AiLeaseGuard;
import com.threadfit.glucopilot.ai.support.lock.AiRateGate;
import com.threadfit.glucopilot.ai.support.lock.InMemoryCacheStore;
import com.threadfit.glucopilot.ai.support.lock.InMemoryCounter;
import com.threadfit.glucopilot.ai.support.lock.InMemoryLeaseGuard;
import com.threadfit.glucopilot.ai.support.lock.InMemoryRateGate;
import com.threadfit.glucopilot.ai.support.lock.RedissonCacheStore;
import com.threadfit.glucopilot.ai.support.lock.RedissonCounter;
import com.threadfit.glucopilot.ai.support.lock.RedissonLeaseGuard;
import com.threadfit.glucopilot.ai.support.lock.RedissonRateGate;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * AI 并发原语的装配：同一种能力，启动期在「Redisson 实现」与「进程内实现」之间二选一。
 *
 * <h2>为什么不用 {@code redisson-spring-boot-starter}</h2>
 * starter 会注册 {@code RedissonConnectionFactory}，把 Spring Data Redis 的连接工厂从 Lettuce
 * 换成 Redisson。我们并不需要那个——项目里 {@code StringRedisTemplate} 的使用者（
 * {@code IdempotencyStore}、系统配置缓存、社区热榜）已经跑得好好的，换驱动只会引入无谓的风险。
 * 所以这里只用 plain {@code org.redisson:redisson}，自己建一个独立的 {@link RedissonClient}。
 *
 * <h2>为什么是 ObjectProvider 而不是直接注入 RedissonClient</h2>
 * {@code backend=memory}（单测/无 Redis 环境）时容器里根本没有那个 Bean，
 * 直接注入会让整个 Ai 模块起不来；{@code ObjectProvider} 让"没有 Redis"成为一种正常配置。
 */
@Slf4j
@Configuration
public class AiConcurrencyConfig {

    /** 连接超时：租约操作必须快失败——这条路径挡在用户请求前面，慢即等于不可用。 */
    private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public RedissonClient aiRedissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setTimeout((int) REDIS_TIMEOUT.toMillis())
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        log.info("AI 并发原语使用 Redisson：{}:{}/db{}", host, port, database);
        return Redisson.create(config);
    }

    /**
     * 时钟：每日额度的"今天"从哪算。抽成 Bean 只为一个目的——单测能把"现在"钉在跨天前后，
     * 否则"次日 00:00 归零"这条规则要在 CI 里等到午夜才能验证。
     */
    @Bean
    public Clock aiClock() {
        return Clock.systemDefaultZone();
    }

    // 注意：进程内实现**不能**注册成 Bean。它们和 Redisson 实现实现同一个接口，
    // 一旦成为容器里的候选就会出现"expected single matching bean but found 2"；
    // 它们只作为降级目标被这里 new 出来（单测也直接 new）。

    @Bean
    public AiLeaseGuard aiLeaseGuard(ObjectProvider<RedissonClient> client,
                                     AiConcurrencyProperties properties) {
        RedissonClient redisson = useRedis(properties) ? client.getIfAvailable() : null;
        InMemoryLeaseGuard fallback = new InMemoryLeaseGuard();
        return redisson == null ? fallback : new RedissonLeaseGuard(redisson, properties, fallback);
    }

    @Bean
    public AiRateGate aiRateGate(ObjectProvider<RedissonClient> client,
                                 AiConcurrencyProperties properties) {
        RedissonClient redisson = useRedis(properties) ? client.getIfAvailable() : null;
        InMemoryRateGate fallback = new InMemoryRateGate();
        return redisson == null ? fallback : new RedissonRateGate(redisson, properties, fallback);
    }

    @Bean
    public AiCacheStore aiCacheStore(ObjectProvider<RedissonClient> client,
                                     AiConcurrencyProperties properties) {
        RedissonClient redisson = useRedis(properties) ? client.getIfAvailable() : null;
        InMemoryCacheStore fallback = new InMemoryCacheStore();
        return redisson == null ? fallback : new RedissonCacheStore(redisson, properties, fallback);
    }

    @Bean
    public AiCounter aiCounter(ObjectProvider<RedissonClient> client,
                               AiConcurrencyProperties properties) {
        RedissonClient redisson = useRedis(properties) ? client.getIfAvailable() : null;
        InMemoryCounter fallback = new InMemoryCounter();
        return redisson == null ? fallback : new RedissonCounter(redisson, properties, fallback);
    }

    private static boolean useRedis(AiConcurrencyProperties properties) {
        return !"memory".equalsIgnoreCase(properties.getBackend());
    }
}
