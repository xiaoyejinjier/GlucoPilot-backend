/**
 * 记忆读取器：会话摘要、最近工具摘要、长期记忆。
 * <p>
 * 读不到一律降级为空、不抛异常——记忆是增强项，不能因为它挂掉整轮对话。
 */
package com.threadfit.glucopilot.ai.memory.reader;
