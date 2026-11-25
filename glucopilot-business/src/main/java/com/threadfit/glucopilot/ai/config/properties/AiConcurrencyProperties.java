package com.threadfit.glucopilot.ai.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块的并发约束参数（前缀 {@code ai.concurrency}）。
 *
 * <h2>为什么单独一个配置类</h2>
 * 这些数字回答的是「花多少钱、占多少连接」这类运营问题，和 {@link AIProperties} 里的
 * 「提示词/模型/温度」不是同一类东西：调参的人往往不同（后者是算法，前者是运维）。
 * 放在一起会让 {@code ai.*} 越来越像杂物间，也让本类的每个阈值缺少解释空间。
 *
 * <h2>四类约束与它们的取舍</h2>
 * <ul>
 *   <li><b>互斥</b>（{@code round-lease-seconds}）：同一轮只允许一个执行者。租约必须明显大于
 *       一轮业务时长（SSE 超时 60 秒），否则正常的长回答会被判成「持有者已死亡」被接管；
 *       但也不能太大，否则进程被 kill 后要等很久才能自愈。90 秒 = 60 秒业务 + 50% 余量。</li>
 *   <li><b>并发流</b>（{@code stream-permits-per-session}）：每个会话在集群范围内同时能开几条
 *       SSE。同会话两条流会交错写消息与记忆（顺序坏掉），所以按会话限 1；
 *       「同时问两件事」靠不同会话并行满足。</li>
 *   <li><b>速率</b>（{@code chat-per-minute}）：令牌桶，按「每分钟多少次请求」算，
 *       拦的是"快速连点/脚本刷"，与并发流互补（并发流拦"同时"，速率拦"累计"）。</li>
 *   <li><b>配额</b>（{@code daily-chat-turns}）：每日轮数的成本闸。速率管不住"一天慢慢来 500 轮"，
 *       只有日累计能管；超出后当天不再放行，次日 00:00 计数自动过期。</li>
 * </ul>
 *
 * <h2>降级</h2>
 * {@code degrade-on-redis-error=true}（默认）时，Redis 不可用会退回进程内实现并打 WARN：
 * 退回的是「今天单实例部署」的行为，聊天不中断；期间多实例可能重复生成，由
 * {@code uk_session_client_active} + 助手消息的确定性 {@code client_msg_id} 兜底。
 * 置 false 则与 {@code IdempotencyStore} 一致——直接报 50000，保证不重复计费但聊天不可用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.concurrency")
public class AiConcurrencyProperties {

    /** {@code redisson}（默认，走 Redis）或 {@code memory}（进程内，仅单测/无 Redis 环境）。 */
    private String backend = "redisson";

    /** Redis 异常时是否降级为进程内实现；false 表示向上抛（与 IdempotencyStore 一致）。 */
    private boolean degradeOnRedisError = true;

    /** 单轮执行权租约（秒）：60 秒业务上限 + 50% 余量。 */
    private int roundLeaseSeconds = 90;

    /**
     * 每个会话同时可开的生成流数量，默认 1。
     * <p>
     * 为什么按会话而不是按用户：同一条会话的两条并发流会同时写 {@code chat_message} 与 ChatMemory，
     * 交错出的 {@code seq_no} 会让下一轮装配出的历史顺序变错（助手A、助手B、用户C），
     * 分片账本也会一轮写两份。而"用户同时问两件事"是真实需求，用**不同会话**并行即可满足，
     * 所以是"每会话 1 路、每用户不限会话数"。
     */
    private int streamPermitsPerSession = 1;

    /** 每用户流租约（秒）：与单轮一致，客户端断连时靠 doFinally 释放，租约只是兜底。 */
    private int streamLeaseSeconds = 90;

    /** 集群范围内同时进行的生成总数（保护上游 QPS）。 */
    private int clusterRoundPermits = 64;

    /** 每用户每分钟可发起的聊天请求数（令牌桶）。 */
    private int chatPerMinute = 20;

    /** 每用户每日轮数上限；{@code <=0} 表示关闭这道闸。 */
    private int dailyChatTurns = 200;

    /** 语音：每用户同时 1 段（固定），此处只配集群总并发。 */
    private int speechClusterPermits = 8;

    /** 语音租约（秒）：识别读超时 45 秒，留 4 倍余量。 */
    private int speechLeaseSeconds = 180;

    /** 后台任务「合并标记」的存活时间（秒）：摘要/抽取执行期间占用，跑完即删。 */
    private int jobDedupSeconds = 600;

    /** 工具结果缓存的条目存活时间（分钟）。 */
    private int toolCacheMinutes = 30;

    /** 每个会话最多缓存多少个工具的最近结果。 */
    private int toolCacheMaxEntries = 16;
}
