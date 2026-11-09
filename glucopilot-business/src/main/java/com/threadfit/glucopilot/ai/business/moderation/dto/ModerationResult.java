package com.threadfit.glucopilot.ai.business.moderation.dto;

/**
 * 一次审核的完整产物：结论 + 落库要用的可追溯信息。
 *
 * @param verdict 判定结果，一定非 {@code null}；调用失败时是 {@link ModerationVerdict#error(String)}
 * @param modelCode 真正被调用的模型编码（取响应元数据，不是配置里的名字）；失败时为空串
 * @param promptVersion 判定所用提示词版本标签（如 {@code moderation@v1}）；取不到时为空串
 */
public record ModerationResult(ModerationVerdict verdict, String modelCode, String promptVersion) {

    public boolean isError() {
        return verdict == null || verdict.isError();
    }
}
