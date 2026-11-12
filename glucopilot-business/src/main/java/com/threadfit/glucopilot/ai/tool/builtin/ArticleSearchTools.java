package com.threadfit.glucopilot.ai.tool.builtin;

import com.threadfit.glucopilot.ai.tool.port.ArticleSearchQuery;
import com.threadfit.glucopilot.ai.tool.runtime.AiToolContext;
import com.threadfit.glucopilot.common.util.JsonUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社区文章检索工具（{@code search_community_articles}）。
 *
 * <p>用途是「社区里有没有人聊过这个」——用户问「有没有人也是餐后高」这类问题时，
 * 引用真实帖子比模型自己编一段经验更可信，也能把用户引回社区。
 *
 * <p>三条刻意的限制：<b>只给摘要不给正文</b>（正文要让用户自己点进去，也避免把一篇长文塞进上下文）、
 * <b>只给可读的文章</b>（过滤在 {@link ArticleSearchQuery} 的实现里，与列表页同一套规则）、
 * <b>条数封顶 5 条</b>（再多模型也读不完，反而挤掉当前话题的上下文）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleSearchTools {

    private static final int DEFAULT_LIMIT = 3;

    private static final int MAX_LIMIT = 5;

    /** 单条摘要的截断长度。 */
    private static final int SUMMARY_MAX_CHARS = 120;

    private static final int KEYWORD_MIN_CHARS = 2;

    private static final int KEYWORD_MAX_CHARS = 40;

    private final ArticleSearchQuery articleSearchQuery;

    private final JsonUtils jsonUtils;

    @Tool(
            name = "search_community_articles",
            description =
                    "在社区里按关键词检索已发布的文章，返回标题、摘要、作者、标签与互动数。"
                            + "用户问「有没有人讨论过」「社区里怎么说」「有没有类似的经历」时使用；"
                            + "回答时要说明来自社区帖子并给出标题，不要把它说成你自己的结论，也不要编造没检索到的内容。"
                            + "只能查已发布且当前用户可读的文章，不含评论与私信。")
    public String searchArticles(
            @ToolParam(description = "检索关键词，2~40 个字，例如「餐后血糖」「胰岛素抵抗」")
                    String keyword,
            @ToolParam(required = false, description = "返回条数，1~5，默认 3")
                    Integer limit,
            ToolContext toolContext) {

        Long userId = AiToolContext.requireUserId(toolContext);

        String normalized = normalize(keyword);
        if (normalized == null) {
            return error("检索关键词不能为空，请给出 2~40 个字的主题词");
        }
        if (normalized.length() < KEYWORD_MIN_CHARS) {
            return error("检索关键词太短，请给出至少 " + KEYWORD_MIN_CHARS + " 个字，例如「餐后血糖」");
        }
        if (normalized.length() > KEYWORD_MAX_CHARS) {
            return error("检索关键词太长，请控制在 " + KEYWORD_MAX_CHARS + " 个字以内");
        }

        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        List<ArticleSearchQuery.Hit> hits;
        try {
            hits = articleSearchQuery.search(userId, normalized, size);
        } catch (RuntimeException e) {
            log.error("工具 search_community_articles 检索失败 userId={} keyword={}", userId, normalized, e);
            return error("检索社区文章时出错，请稍后重试");
        }
        if (hits == null || hits.isEmpty()) {
            hits = List.of();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keyword", normalized);
        payload.put("itemCount", hits.size());
        payload.put("items", items(hits));
        payload.put(
                "hint",
                hits.isEmpty()
                        ? "社区里没有检索到相关文章。如实告诉用户「社区里暂时没找到相关讨论」，不要编造帖子。"
                        : "这些是社区里的真实帖子：引用时说明来自社区并给出标题与作者，"
                                + "细节让用户自己点进帖子看；不要补写帖子里没有的结论。");
        log.info("工具 search_community_articles 执行 userId={} keyword={} 命中 {}", userId, normalized, hits.size());
        return jsonUtils.toJson(payload);
    }

    private static List<Map<String, Object>> items(List<ArticleSearchQuery.Hit> hits) {
        List<Map<String, Object>> list = new ArrayList<>(hits.size());
        for (ArticleSearchQuery.Hit hit : hits) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", hit.title());
            map.put("summary", trim(hit.summary(), SUMMARY_MAX_CHARS));
            map.put("author", hit.authorName());
            map.put("tags", hit.tags());
            map.put("publishedAt", hit.publishedAt());
            map.put("likeCount", hit.likeCount());
            map.put("commentCount", hit.commentCount());
            list.add(map);
        }
        return list;
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String flat = keyword.replaceAll("\\s+", " ").trim();
        return flat.isEmpty() ? null : flat;
    }

    private static String trim(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private String error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return jsonUtils.toJson(map);
    }
}
