package com.threadfit.glucopilot.ai.model.service;

import com.threadfit.glucopilot.ai.model.dto.AiRuntimeStatusDtos;

/**
 * 运行时配置诊断：把「此刻快照 + 场景绑定」合成一份可解释的现状。
 *
 * <p>只读，不触发加载（用的是 {@code snapshot()} 已有的 TTL 语义，最多 60s 旧）。
 * 存在的意义是把「为什么是这个模型」从日志与代码里搬到接口上，供后台与排障脚本消费。
 */
public interface AiRuntimeStatusService {

    AiRuntimeStatusDtos.Status status();
}
