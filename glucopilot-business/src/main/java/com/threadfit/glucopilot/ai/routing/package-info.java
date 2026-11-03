/**
 * 场景路由：给定场景，决定用哪个模型、发哪些参数。
 * <p>
 * 优先级：场景绑定（DB，视觉还要求能力含 vision）→ 用途默认（DB）→ 通道 yaml 兜底。
 * <p>
 * 这是唯一决策点：别处不应再自己拼 model/参数。
 */
package com.threadfit.glucopilot.ai.routing;
