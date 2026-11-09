/**
 * 翻译能力的默认实现：{@link com.threadfit.glucopilot.ai.business.translation.service.impl.ModelEnglishTranslator}。
 *
 * <p>一次调用要同时满足 JSON 输出与"数字不走样"，所以带一次重试：第一次不合格就把纠正指令追加到 system 消息再试一遍，
 * 两次都不过就抛异常让调用方把缓存标 FAILED（绝不返回半成品译文）。
 */
package com.threadfit.glucopilot.ai.business.translation.service.impl;