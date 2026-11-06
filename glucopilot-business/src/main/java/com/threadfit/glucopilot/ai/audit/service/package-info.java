/**
 * AI 审计的读取契约：四张只读列表。
 *
 * <p>接口只暴露「查」：审计面不提供删除/导出/改状态，删数据的权力留在各业务域
 * （用户注销走 {@code purgeByUserId}，运营不能从审计页抹掉证据）。
 */
package com.threadfit.glucopilot.ai.audit.service;
