package com.threadfit.glucopilot.ai.business.ocr.service;

import com.threadfit.glucopilot.ai.business.ocr.dto.OcrDtos;

/**
 * 血糖仪读数识别任务契约（接口 2.21 / 2.22）。
 *
 * <h2>为什么是"任务"而不是同步接口</h2>
 * 视觉识别既慢又贵：创建任务立刻返回 taskId，识别在后台线程池里跑，客户端轮询详情。
 * 这样超时/重试都不会重复扣费——幂等靠调用方带的 Idempotency-Key（有快照键时直接复用结果）。
 *
 * <h2>归属强校验</h2>
 * 必须校验 mediaId 属于当前用户且业务类型匹配：缺了这一步，用户 A 可以传 B 的 mediaId，
 * 服务端会把 B 的图片读出来识别给 A 看——那是实质性的越权内容泄露。
 */
public interface OcrTaskService {

    /** 2.21 创建识别任务（幂等键存在时复用既有结果）。 */
    OcrDtos.TaskView create(OcrDtos.CreateRequest request, Long userId);

    /** 2.22 查询任务详情（只能查自己的）。 */
    OcrDtos.TaskView detail(Long id, Long userId);

    /**
     * 账号注销：物理删除该用户的识别任务记录。
     * <p>识别结果含血糖读数，属敏感健康数据，注销后不留行。
     */
    void purgeAccountData(Long userId);
}