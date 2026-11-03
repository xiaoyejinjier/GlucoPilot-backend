/**
 * 会话历史装配：把「历史 + 摘要 + 工具摘要 + 长期记忆 + 本轮消息」按 token 预算装成消息列表。
 * <p>
 * 纯函数组件，可被单测穷举预算组合；预算只做安全阀，保底条数优先。
 */
package com.threadfit.glucopilot.ai.memory.assembly;
