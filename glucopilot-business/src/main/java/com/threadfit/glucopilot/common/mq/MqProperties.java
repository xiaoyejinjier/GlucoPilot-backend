package com.threadfit.glucopilot.common.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 消息队列的「业务拓扑」配置（前缀 {@code glucopilot.mq}）。
 *
 * <h2>为什么要与 {@code spring.rabbitmq.*} 分开</h2>
 * {@code spring.rabbitmq.*} 描述的是「怎么连上代理」（地址、凭据、vhost、确认模式），
 * 属于运维参数；这里描述的是「我们的交换机与队列叫什么」，属于业务契约。
 * 混在一起会导致两个问题：一是换环境（本地 / p10）时业务名也被覆盖；
 * 二是队列名散落在 Java 字符串里，改名要全仓搜。
 *
 * <h2>当前拓扑只有一个「通用事件箱」</h2>
 * 基础配置阶段刻意只声明**一条**队列（{@link #queue}）＋一条死信队列（{@link #deadLetterQueue}），
 * 绑定在 topic exchange 的 {@link #queueRoutingKey} 上。这样任何后续业务（审核派发、导出任务、
 * 通知重试）都可以先用 {@code routingKey = "core.xxx"} 直接发事件，不需要再动基础设施代码；
 * 等某个业务的消费特征（并发度、重试节奏）明显不同时，再为它单开队列与死信。
 */
@Data
@Component
@ConfigurationProperties(prefix = "glucopilot.mq")
public class MqProperties {

    /** 总开关：关掉时不声明拓扑、{@code MqPublisher} 直接报错而不是静默丢弃。 */
    private boolean enabled = true;

    /** 业务事件交换机（topic、持久化）。 */
    private String exchange = "glucopilot.event";

    /** 死信交换机（topic、持久化）。 */
    private String deadLetterExchange = "glucopilot.event.dlx";

    /** 通用事件队列：目前所有 {@code core.*} 事件都进这里。 */
    private String queue = "glucopilot.event.core";

    /** 通用队列的绑定键。用 {@code core.#} 而不是 {@code #}，给未来的其它域留出隔离空间。 */
    private String queueRoutingKey = "core.#";

    /** 通用事件队列的死信队列。 */
    private String deadLetterQueue = "glucopilot.event.core.dlq";

    /** 死信队列的绑定键：死信交换机上 {@code #} 全收，避免因为路由键写错而把失败消息丢掉。 */
    private String deadLetterRoutingKey = "#";
}
