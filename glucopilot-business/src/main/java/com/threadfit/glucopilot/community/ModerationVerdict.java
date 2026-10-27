package com.threadfit.glucopilot.community;

import java.util.List;

/**
 * 一次 AI 判定结果（不可变）。
 *
 * @param verdict    三态：{@code PASS} / {@code REJECT} / {@code ERROR}。与库里的
 *                   {@code community_article.ai_verdict}、{@code community_comment.ai_verdict} 同一套取值，
 *                   前端映射表已按这三个值写好，不要新增第四种。
 * @param category   主命中类别（{@link ModerationCategories}）；{@code PASS} 时为 {@code null}
 * @param severity   严重度 1 低 / 2 中 / 3 高；{@code PASS} 时为 0
 * @param confidence 模型置信度 0~100；缺失按 0（0 不会触发"高置信度自动拒绝"，只会转人工）
 * @param reason     模型给出的中文说明（≤200 字，不摘抄原文），落库前由调用方截到 1000 字列宽
 * @param hits       解析出的逐条命中（可能多条；主类别取严重度最高的那条）
 */
public record ModerationVerdict(String verdict,
                                ModerationCategories category,
                                int severity,
                                int confidence,
                                String reason,
                                List<Hit> hits) {

    /** 判定结果常量：与前端/库表枚举一致。 */
    public static final String PASS = "PASS";
    public static final String REJECT = "REJECT";
    public static final String ERROR = "ERROR";

    /**
     * 单条命中。
     *
     * @param excerpt 模型回吐的"命中原句"（≤40 字）；用于反查原文计算偏移，<b>不落库</b>
     */
    public record Hit(ModerationCategories category, int severity, int confidence, String excerpt) {
    }

    public boolean isPass() {
        return PASS.equals(verdict);
    }

    public boolean isReject() {
        return REJECT.equals(verdict);
    }

    public boolean isError() {
        return ERROR.equals(verdict);
    }

    /** 库表 {@code ai_category} 列的取值；PASS 写 null（不要写空串，null 才能区分"没审"与"审过且干净"）。 */
    public String categoryCode() {
        return category == null ? null : category.code();
    }

    /**
     * 解析失败/调用失败时的统一结果。
     * <p>
     * <b>失败绝不等于通过</b>：审核系统里"模型没给出可解析结论"与"内容干净"是两件完全不同的事。
     * 文章侧 ERROR 会进 {@code AI_ERROR} 让人工处理；评论侧 ERROR 保持可见但 {@code ai_verdict} 记为 ERROR
     * （先发后审不能因为模型故障就把新评论全部隐藏，但也不能当成通过）。
     */
    public static ModerationVerdict error(String reason) {
        return new ModerationVerdict(ERROR, null, 0, 0, reason, List.of());
    }

    /** 模型表示"没有违规"时的结果。 */
    public static ModerationVerdict pass(String reason) {
        return new ModerationVerdict(PASS, null, 0, 0, reason == null ? "" : reason, List.of());
    }
}
