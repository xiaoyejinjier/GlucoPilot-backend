package com.threadfit.glucopilot.ai.model.runtime;

/**
 * 账单写入出口。
 *
 * <p>定义成接口只为一件事：{@code DynamicChatModel} 用 {@code ObjectProvider<AiCallRecorder>} 懒取，
 * 没有实现（单测、{@code spring.ai.model.chat=none} 的无 Key 部署）就静默不记，
 * 而不是让容器启动失败——账单是观测设施，不能成为主链路的硬依赖。
 *
 * <p>实现<u>必须</u>自己吞掉全部异常：一次记账失败不能把用户的回复变成 500。
 */
public interface AiCallRecorder {

    /** 记一次调用。实现必须非阻塞、且绝不抛异常。 */
    void record(AiCallRecord record);
}
