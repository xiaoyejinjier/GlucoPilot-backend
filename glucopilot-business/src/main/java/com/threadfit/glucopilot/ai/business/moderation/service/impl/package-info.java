/**
 * 审核能力的默认实现：{@link com.threadfit.glucopilot.ai.business.moderation.service.impl.ModelContentModerationService}。
 *
 * <p>走 utility client（无工具、无 advisor）+ 审核场景提示词，45s 超时；任何失败都收敛成 ERROR 结论，不抛异常。
 */
package com.threadfit.glucopilot.ai.business.moderation.service.impl;