package com.threadfit.glucopilot.community.article.service;

import com.threadfit.glucopilot.ai.tool.port.ArticleSearchQuery;
import com.threadfit.glucopilot.community.article.dto.PublicationDtos;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ArticleSearchQuery} 的社区侧实现：直接复用列表页的检索语义。
 *
 * <p><b>为什么不是另写一条 SQL</b>：社区列表已经把「已发布 + 可见性 + 隐藏/删除」这套过滤
 * 收敛在 {@link CommunityPublicationService#feed} 里（含个人可见、关注可见等规则）。
 * AI 工具另写一条查询，就等于把这套规则复制一份，任何一次调整都会漏掉 AI 这条路径——
 * 那不是性能问题，是会泄露越权内容的正确性问题。
 *
 * <p>排序固定用 {@code LATEST}：AI 要回答「最近有没有人讨论过」，按时间更像是它需要的；
 * 推荐排序里混着用户画像与热度衰减，模型无法解释它。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityArticleSearchQuery implements ArticleSearchQuery {

    /** 与列表页一致的 sort 取值。 */
    private static final String SORT_LATEST = "LATEST";

    private final CommunityPublicationService publication;

    @Override
    public List<Hit> search(long viewer, String keyword, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        PageQuery page = new PageQuery();
        page.setPageNum(1);
        // 只取这么多行：AI 工具的结果要有上限，翻页对它没有意义
        page.setPageSize(limit);

        PageResult<PublicationDtos.Article> result =
                publication.feed(viewer, null, false, keyword, SORT_LATEST, page);
        List<PublicationDtos.Article> records = result == null ? null : result.getRecords();
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>(records.size());
        for (PublicationDtos.Article article : records) {
            if (hits.size() >= limit) {
                break;
            }
            hits.add(toHit(article));
        }
        log.debug("社区文章检索 viewer={} keyword={} limit={} 命中 {}", viewer, keyword, limit, hits.size());
        return hits;
    }

    private static Hit toHit(PublicationDtos.Article article) {
        String author = article.author() == null ? null : article.author().nickname();
        return new Hit(
                String.valueOf(article.id()),
                article.title(),
                article.summary(),
                author,
                tagNames(article.tags()),
                article.publishAt() == null ? null : article.publishAt().toString(),
                article.likeCount(),
                article.commentCount());
    }

    /** 只取标签名：模型不需要编码与启用状态。 */
    private static List<String> tagNames(List<PublicationDtos.Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(java.util.Objects::nonNull)
                .map(PublicationDtos.Tag::name)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
