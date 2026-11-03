/**
 * 跨域共用的工具（判据：至少 2 个域在用）。
 * <p>
 * 目前只有 {@code ChatRoundGuard}（同一轮单飞）与 {@code TokenEstimator}（token 估算）。只服务单一域的类请放进该域的 support/。
 */
package com.threadfit.glucopilot.ai.support;
