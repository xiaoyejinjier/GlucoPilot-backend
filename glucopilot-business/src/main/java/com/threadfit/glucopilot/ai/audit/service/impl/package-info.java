/**
 * AI 审计读模型的默认实现：{@link com.threadfit.glucopilot.ai.audit.service.impl.AiAuditServiceImpl}。
 *
 * <p>实现里只做三件事：参数归一（去空白、限长）、查库（复用各业务域既有 mapper 新增的分页方法）、
 * 把实体摊平成审计行。刻意不带任何业务判断——审计页要展示的是「发生过什么」，
 * 而不是「应该发生什么」。
 */
package com.threadfit.glucopilot.ai.audit.service.impl;
