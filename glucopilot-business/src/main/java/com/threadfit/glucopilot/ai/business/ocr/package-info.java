/**
 * 血糖仪读数识别场景（接口 2.21 创建 OCR 任务 / 2.22 查询 OCR 任务 / 场景编码 {@code SceneCode.OCR_GLUCOSE}）。
 * <p>
 * 已实现为「创建任务 → 轮询 → 用户确认」的异步任务流
 * （{@code ocr_task} 表已建好，含 {@code ocr_result_json} 与 {@code schemaVersion}），
 * 而不是 SSE 流式。
 * <p>
 * 关键约束：
 * <ul>
 *   <li>媒体上传使用接口 2.14 / 2.15，图片业务类型必须为 {@code GLUCOSE}；</li>
 *   <li>识别通道选型：专用 OCR 服务优先、通用视觉模型兜底。血糖读数错一位即医疗风险，
 *       窄域专用 OCR 在这类数字读数上通常更稳；</li>
 *   <li>提示词：{@code prompts/ocr-glucose/system.md}（已就绪，强制「看不清就返回 null、不猜测」）。</li>
 * </ul>
 * 任务状态机为 0 待处理 / 1 进行中 / 2 成功 / 3 失败；运营侧监控接口 3.34 仍归管理端。
 */
package com.threadfit.glucopilot.ai.business.ocr;
