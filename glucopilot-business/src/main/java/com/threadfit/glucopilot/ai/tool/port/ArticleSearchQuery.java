package com.threadfit.glucopilot.ai.tool.port;

import java.util.List;

/**
 * 社区文章检索端口（{@code search_community_articles} 的数据来源）。
 *
 * <p>契约留在 {@code ai}（工具要声明自己的入参与返回），实现留在 {@code community}
 * —— 那里才认识可见性、隐藏与审核状态。实现方必须复用<b>与社区列表完全相同的可读过滤</b>
 * （已发布 + 当前浏览者可见），否则会出现「AI 引用了 Feed 里看不到的文章」。
 */
public interface ArticleSearchQuery {

    /**
     * 一条给模型看的文章摘要视图。
     *
     * @param id 文章标识（仅用于让模型在多次结果间区分，不对外暴露路由）
     * @param title 标题
     * @param summary 平台摘要（可能为空；工具层会自行截断）
     * @param authorName 作者昵称（可能为空）
     * @param tags 标签名列表（可能为空）
     * @param publishedAt 发布时间，ISO 字符串（可能为空）
     * @param likeCount 点赞数
     * @param commentCount 评论数
     */
    record Hit(
            String id,
            String title,
            String summary,
            String authorName,
            List<String> tags,
            String publishedAt,
            long likeCount,
            long commentCount) {
    }

    /**
     * 按关键词检索当前用户可读的文章。
     *
     * @param viewer 浏览者用户 id（可见性判定要用）
     * @param keyword 关键词（实现方按社区列表同样的规则处理空白与长度）
     * @param limit 最多返回多少条（调用方已封顶，实现方可再兜底）
     * @return 命中的文章，顺序即展示顺序；没有命中返回空列表而不是 null
     */
    List<Hit> search(long viewer, String keyword, int limit);
}
