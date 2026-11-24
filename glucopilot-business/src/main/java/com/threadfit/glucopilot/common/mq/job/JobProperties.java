package com.threadfit.glucopilot.common.mq.job;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 后台任务队列的配置（{@code glucopilot.mq.jobs.*}）。
 *
 * <h2>为什么开关有两层</h2>
 * {@code glucopilot.mq.enabled} 管「连不连 MQ」，本类的 {@code enabled} 管「用不用任务队列」。
 * 分开是因为存在这种部署：MQ 连得上、也在用它发领域事件，但任务队列还没灰度开。
 * 只有两层都为 true 时才注册拓扑与监听器——少注册一次比注册后没人消费要安全。
 *
 * <h2>{@code event-driven} 与补扫的关系</h2>
 * {@code event-driven=true}：消息是主通道，补扫只兜底（5 分钟一次）。
 * {@code event-driven=false}：不注册监听器，消息只作观测，业务回退到原来的轮询/线程池路径
 * ——这是每个批次上线时的回退开关，出问题不用回滚代码，改配置重启即可。
 */
@Data
@Component
@ConfigurationProperties(prefix = "glucopilot.mq.jobs")
public class JobProperties {

    /** 任务队列总开关（false 时不注册拓扑、监听器、补扫、重投）。 */
    private boolean enabled = true;

    /** 是否以消息为主通道；false 时业务仍走原有轮询/线程池。 */
    private boolean eventDriven = true;

    /** 补扫开关（即使 event-driven=false 也可以单独开，用于对比两套路径的结果）。 */
    private boolean reconcileEnabled = true;

    /** 补扫间隔（秒）。5 分钟：既不会让丢的消息等太久，也不会把 DB 扫成热点。 */
    private int reconcileIntervalSeconds = 300;

    /** 单次补扫每个域最多投多少条，防止积压时一次性灌爆队列。 */
    private int reconcileBatchSize = 200;

    /** 死信重投的轮询间隔（毫秒）。60 秒 ≈ 我们愿意承受的最大延迟。 */
    private long replayIntervalMs = 60_000L;

    /** 最多重投几次（不含首次投递）。3 次之后留在 DLQ 由人处置，避免毒消息无限打转。 */
    private int maxAttempts = 3;

    /** 审核队列的消费者并发（审核要调模型，4 路足够把 12 条/分提到 ~60/分）。 */
    private int moderationConcurrency = 4;

    /** 记忆抽取并发（每轮抽取要读会话 + 调模型，2 路足够，且不该挤占审核）。 */
    private int memoryConcurrency = 2;

    /** OCR 并发（第三方识别接口有配额，2 路保守）。 */
    private int ocrConcurrency = 2;

    /** 每个消费者的预取条数。2 而不是 10：任务耗时长（模型/识别），预取太多会让其它实例空等。 */
    private int prefetch = 2;

    /** 任务交换机。 */
    private String exchange = "glucopilot.job";

    /** 任务死信交换机。 */
    private String deadLetterExchange = "glucopilot.job.dlx";

    /** 审核队列。 */
    private String moderationQueue = "glucopilot.job.moderation";

    /** 记忆抽取队列。 */
    private String memoryQueue = "glucopilot.job.memory";

    /** OCR 队列。 */
    private String ocrQueue = "glucopilot.job.ocr";

    /** 死信队列名约定：业务队列名 + {@code .dlq}。 */
    public static String dlqOf(String queue) {
        return queue + ".dlq";
    }
}
