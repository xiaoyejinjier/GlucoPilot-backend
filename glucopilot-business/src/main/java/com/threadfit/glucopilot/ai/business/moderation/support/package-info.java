/**
 * 审核的两个"死件"：模型输出的解析器与类别词表。
 *
 * <p>都不持状态、不发请求，纯靠输入决定输出——所以能单测到"模型胡说八道时也绝不返回 PASS"。
 */
package com.threadfit.glucopilot.ai.business.moderation.support;