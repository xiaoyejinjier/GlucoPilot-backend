/**
 * 血糖仪读数识别场景（接口 2.21 创建 OCR 任务 / 2.22 查询 OCR 任务 / 场景编码 {@code SceneCode.OCR_GLUCOSE}）。
 * <p>
 * 现状：<b>待实现</b>。与对话场景的契约形态完全不同——它是「创建任务 → 轮询 → 用户确认」的异步任务流
 * （{@code ocr_task} 表已建好，含 {@code ocr_result_json} 与 {@code schemaVersion}），
 * 而不是 SSE 流式。
 * <p>
 * 依赖与前置：
 * <ul>
 *   <li>媒体上传（接口 2.14 / 2.15，{@code media} 模块）——当前 {@code media_file} 相关接口未实现，是跨模块阻塞项；</li>
 *   <li>识别通道选型：专用 OCR 服务优先、通用视觉模型兜底。血糖读数错一位即医疗风险，
 *       窄域专用 OCR 在这类数字读数上通常更稳；</li>
 *   <li>提示词：{@code prompts/ocr-glucose/system.md}（已就绪，强制「看不清就返回 null、不猜测」）。</li>
 * </ul>
 * 计划落点：{@code service/OcrTaskService}（任务状态机 0 待处理 / 1 进行中 / 2 成功 / 3 失败）+
 * {@code controller/OcrTaskController}（2.21 / 2.22）；运营侧监控接口 3.34 归管理端。
 */
package com.threadfit.glucopilot.ai.business.ocr;
