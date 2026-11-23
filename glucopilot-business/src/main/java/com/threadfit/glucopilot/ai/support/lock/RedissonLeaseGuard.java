package com.threadfit.glucopilot.ai.support.lock;

import com.threadfit.glucopilot.ai.config.properties.AiConcurrencyProperties;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 租约实现：多实例共享的"有限名额 + 可跨线程释放"。
 *
 * <h2>为什么是 {@link RPermitExpirableSemaphore} 而不是 {@code RLock}</h2>
 * {@code RLock} 把持有者绑定到线程（构建期记录 threadId），{@code unlock()} 必须由同一线程调用，
 * 否则抛 {@code IllegalMonitorStateException}。我们的轮次锁在请求线程获取、在 SSE 终局的
 * Netty 线程释放，用 {@code RLock} 必然报错。许可信号量每次获取返回**独立的 permit id**，
 * 官方 javadoc 明确 "Each permit identified by own id and could be released only using its id"，
 * 任何线程拿这个 id 都能释放，正好对上原来的 token 语义。
 *
 * <h2>为什么显式给 leaseTime（不开看门狗）</h2>
 * 不带 leaseTime 的 `lock()` 才会启用看门狗续期，代价是每把锁一个后台续期任务；
 * 我们的租约是"一次生成的保护"，正常路径 60 秒内一定结束，给 90 秒固定租约比续期更简单，
 * 且与进程内实现原来的 `STALE_AFTER=5min` 语义一致（过期即视作持有者已死）。
 *
 * <h2>为什么 waitTime = 0</h2>
 * 抢不到就应该立刻告诉用户"这一条正在生成中"，而不是把他挂在这里等——排队会让超时表现更糟。
 */
public class RedissonLeaseGuard extends RedissonSupport implements AiLeaseGuard {

    private final RedissonClient client;

    private final InMemoryLeaseGuard fallback;

    public RedissonLeaseGuard(RedissonClient client, AiConcurrencyProperties properties, InMemoryLeaseGuard fallback) {
        super(properties);
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public String tryAcquire(String key, int permits, Duration lease) {
        try {
            RPermitExpirableSemaphore semaphore = client.getPermitExpirableSemaphore(key);
            // 首次创建时设容量；已存在返回 false（配置调大后需要手工删键，见 application.yaml 注释）
            semaphore.trySetPermits(Math.max(permits, 1));
            return semaphore.tryAcquire(0, lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 等 0 秒不该被 interrupt，但真被中断时要还回中断标记，别让它静默丢失
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            if (shouldDegrade("lease.acquire", e)) {
                return fallback.tryAcquire(key, permits, lease);
            }
            throw e;
        }
    }

    @Override
    public boolean release(String key, String token) {
        if (token == null) {
            return false;
        }
        try {
            return client.getPermitExpirableSemaphore(key).tryRelease(token);
        } catch (RuntimeException e) {
            if (shouldDegrade("lease.release", e)) {
                return fallback.release(key, token);
            }
            throw e;
        }
    }

    @Override
    public int held(String key) {
        try {
            return client.getPermitExpirableSemaphore(key).acquiredPermits();
        } catch (RuntimeException e) {
            if (shouldDegrade("lease.held", e)) {
                return fallback.held(key);
            }
            throw e;
        }
    }
}
