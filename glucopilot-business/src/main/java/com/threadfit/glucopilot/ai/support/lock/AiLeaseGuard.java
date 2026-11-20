package com.threadfit.glucopilot.ai.support.lock;

import java.time.Duration;

/**
 * 分布式租约（"同一 key 最多 N 个执行者"）。
 *
 * <h2>为什么是"租约 + 令牌"而不是普通的 lock/unlock</h2>
 * 调用方的加锁与释放<b>不在同一个线程</b>：聊天在请求线程拿到执行权，在流式终局
 * （通常是 Netty 线程）释放。Java 的 {@code ReentrantLock}、Redisson 的 {@code RLock}
 * 都是<b>线程绑定</b>的，跨线程释放会抛 {@code IllegalMonitorStateException}。
 * 所以这里用「持有者拿一个不透明令牌、凭令牌释放」的模型：
 * <ul>
 *   <li>令牌本身就是 Redisson {@code RPermitExpirableSemaphore} 的 permit id，任何线程都能释放；</li>
 *   <li>凭令牌释放天然幂等且不会误删别人——这正是 {@code ChatRoundGuard} 当年引入令牌的原因；</li>
 *   <li>{@code lease} 到期后占位自动消失，进程被 kill 不需要人工干预。</li>
 * </ul>
 *
 * <h2>{@code permits} 的双重含义</h2>
 * 它既是"容量"也是"互斥度"：{@code permits=1} 表示互斥（同一 key 同时只有一个执行者），
 * {@code permits=8} 表示分布式信号量（全局最多 8 个并发）。同一个原语覆盖两种语义，
 * 不需要为"锁"和"信号量"写两套代码。
 */
public interface AiLeaseGuard {

    /**
     * 尝试占用一个名额。
     *
     * @param key     业务键（已含命名空间前缀）
     * @param permits 该 key 的容量；首次调用时创建
     * @param lease   单次占用最长存活时间，到期自动释放
     * @return 持有令牌；拿不到名额时返回 {@code null}（调用方决定报什么错）
     */
    String tryAcquire(String key, int permits, Duration lease);

    /**
     * 按令牌释放。未持有、已被接管或令牌无效时返回 {@code false}，不会误删别人的名额。
     */
    boolean release(String key, String token);

    /** 当前被占用的名额数（用于观测/测试；分布式实现下是全集群视角）。 */
    int held(String key);
}
