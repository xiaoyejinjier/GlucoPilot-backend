/**
 * 审核能力的入参与结果：{@link com.threadfit.glucopilot.ai.business.moderation.dto.ModerationRequest 送审内容}、
 * {@link com.threadfit.glucopilot.ai.business.moderation.dto.ModerationResult 判定结果}、
 * {@link com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict 三态结论}。
 *
 * <p>它们是能力对外的契约，因此和解析器、类别词表分开放：改输出协议时先看这里。
 */
package com.threadfit.glucopilot.ai.business.moderation.dto;