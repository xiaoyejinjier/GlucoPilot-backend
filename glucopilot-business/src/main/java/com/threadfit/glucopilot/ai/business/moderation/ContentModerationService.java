package com.threadfit.glucopilot.ai.business.moderation;

/**
 * 内容审核能力：{@link ModerationRequest 一段文字} 进，{@link ModerationResult 判定结果} 出。
 *
 * <h2>边界</h2>
 *
 * <b>不知道文章、不知道评论、不碰任何业务表</b>：谁送来审、判定完怎么落库、通过后要不要发布， 全部由调用方（社区模块的定时器与控制器）决定。能力侧只做三件事——取当前生效的审核提示词、
 * 发起一次模型调用、把输出解析成三态结论。
 *
 * <h2>失败语义（接口承诺，实现必须遵守）</h2>
 *
 * 实现<b>不抛异常</b>：模型不可用、超时、输出不可解析，一律返回 {@code ERROR} 结论。 审核系统里"模型没给出可解析结论"与"内容干净"是两件完全不同的事，把它们混在一起是这类系统最常见的事故来源。
 */
public interface ContentModerationService {

    /**
     * 判定一段内容。
     *
     * @param request 送审内容，非 {@code null}
     * @return 一定非 {@code null}；失败时 {@link ModerationResult#isError()} 为 {@code true}
     */
    ModerationResult judge(ModerationRequest request);

    /** 当前生效的审核提示词版本标签（如 {@code moderation@v1}）；取不到返回空串。 */
    String promptVersion();
}
