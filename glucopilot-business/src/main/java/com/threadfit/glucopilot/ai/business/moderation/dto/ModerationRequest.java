package com.threadfit.glucopilot.ai.business.moderation.dto;

import java.util.List;

/**
 * 送审内容（纯数据，不含任何业务表字段）。
 *
 * <h2>为什么单独定义一个请求对象</h2>
 *
 * 审核能力是"给一段文字，回答它是否违规"。业务侧（社区文章、社区评论、将来可能的私信）负责 把各自的实体摊平成这几个字段，能力侧只负责拼提示词、调模型、解析结论。这样 {@code
 * ai} 包永远不会反向依赖 {@code community}。
 *
 * @param kind 内容类型的中文名（{@link #KIND_ARTICLE} / {@link #KIND_COMMENT}）：提示词对两者的 容忍度不同（文章后面还有人工终审，评论没有），
 *     所以类型本身要进提示词
 * @param title 标题；评论没有标题，传 {@code null}
 * @param tags 标签；评论没有标签，传空集合
 * @param body 正文原文，<b>不截断</b>——截断会漏掉长文后半段的违规
 */
public record ModerationRequest(String kind, String title, List<String> tags, String body) {

    /** 文章：通过后进人工终审。 */
    public static final String KIND_ARTICLE = "文章";

    /** 评论：先发后审，没有人工终审兜底，判错的代价更高。 */
    public static final String KIND_COMMENT = "评论";

    public ModerationRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static ModerationRequest article(String title, List<String> tags, String body) {
        return new ModerationRequest(KIND_ARTICLE, title, tags, body);
    }

    public static ModerationRequest comment(String body) {
        return new ModerationRequest(KIND_COMMENT, null, List.of(), body);
    }

    public boolean isComment() {
        return KIND_COMMENT.equals(kind);
    }

    /**
     * 渲染成送审文本：类型 + 标题 + 标签 + 正文。
     *
     * <p>这里刻意用<b>标注式纯文本</b>而不是 JSON：模型偶发会把 JSON 输入当成输出模板照抄， 而审核的输出协议是逐行字段清单，两者混在一起最容易解析失败。
     */
    public String promptText() {
        StringBuilder sb = new StringBuilder(isComment() ? "类型：评论\n" : "类型：文章\n");
        if (title != null && !title.isBlank()) {
            sb.append("标题：").append(title.trim()).append('\n');
        }
        if (!tags.isEmpty()) {
            sb.append("标签：").append(String.join("、", tags)).append('\n');
        }
        sb.append("正文：\n").append(body == null ? "" : body);
        return sb.toString();
    }
}
