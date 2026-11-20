package com.threadfit.glucopilot.ai.support.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * 分布式小缓存（"一张按会话/用户划分的表，每个条目自带 TTL"）。
 *
 * <h2>两个使用场景</h2>
 * <ul>
 *   <li><b>后台任务的合并标记</b>：{@code putIfAbsent} 成功 = "我来跑"，失败 = "已经有人在跑，跳过"。
 *       摘要与记忆抽取不是互斥资源，它们只是"同一会话不需要跑两遍"，用标记比用锁更贴语义，
 *       而且永远不会产生用户可见的失败。</li>
 *   <li><b>工具结果缓存</b>：同一会话、同一工具、同一入参在 N 分钟内直接命中，
 *       省掉一次数据库查询与一次模型往返。</li>
 * </ul>
 *
 * <h2>为什么按"表 + 字段"而不是扁平键</h2>
 * 因为 TTL 的粒度不同：会话结束时只想删"这张会话的工具缓存"，
 * 用一张表（{@code ai:toolrepeat:{sessionId}}）就能一次 {@link #deleteAll}，
 * 而扁平键需要 SCAN 前缀清理——在共享 Redis 上 SCAN 是要被 DBA 找上门的操作。
 */
public interface AiCacheStore {

    Optional<String> get(String mapKey, String field);

    /**
     * 原子写入：仅当字段不存在时写入。
     *
     * @return {@code true} 表示本次写入成功（"我抢到了"），{@code false} 表示已存在
     */
    boolean putIfAbsent(String mapKey, String field, String value, Duration ttl);

    void put(String mapKey, String field, String value, Duration ttl);

    void delete(String mapKey, String field);

    /**
     * 按字段名条件删除（一次遍历、不 SCAN）：用于'某个工具的结果全部作废'这类细粒度失效。
     * 表本身很小（单会话十几条），遍历成本可以忽略。
     */
    void deleteIf(String mapKey, java.util.function.Predicate<String> fieldPredicate);

    /** 删掉整张表（会话结束/显式失效时用）。 */
    void deleteAll(String mapKey);
}

