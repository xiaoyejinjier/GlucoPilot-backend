/**
 * AI 审计的 HTTP 入口：{@link com.threadfit.glucopilot.ai.audit.controller.AdminAiAuditController}。
 *
 * <p>四个 {@code GET}、一个权限点（{@code ai:chat:audit}），控制器里没有条件分支——
 * 「查什么」交给 service，参数校验交给 {@code @Valid PageQuery} 与 service 内的归一。
 */
package com.threadfit.glucopilot.ai.audit.controller;
