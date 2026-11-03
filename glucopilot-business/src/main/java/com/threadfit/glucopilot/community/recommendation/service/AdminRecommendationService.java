package com.threadfit.glucopilot.community.recommendation.service;

import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.article.mapper.CommunityArticleQuery;
import com.threadfit.glucopilot.community.article.mapper.CommunityPublicationMapper;
import com.threadfit.glucopilot.community.recommendation.dto.AdminRecommendationDtos.*;
import com.threadfit.glucopilot.community.recommendation.mapper.AdminRecommendationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminRecommendationService {
    private final CommunityRecommendationService recommendation;
    private final CommunityPublicationMapper articles;
    private final AdminRecommendationMapper mapper;

    @Transactional(readOnly = true)
    public Report report(long userId) {
        if (userId < 1) throw new BizException(ErrorCode.BAD_REQUEST, "用户ID应为正整数");
        var user = mapper.user(userId);
        if (user == null || user.isEmpty())
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在或已停用");
        var analysis = recommendation.analyze(userId);
        var context = analysis.recommendation();
        var query = new CommunityArticleQuery();
        query.setViewer(userId);
        query.setPublished(true);
        query.setReadable(true);
        query.setRecommendation(context);
        query.setExplainRecommendation(true);
        long total = articles.countArticles(query);
        var rows = articles.selectArticles(query, 20, 0);
        var names =
                mapper.tags().stream().collect(Collectors.toMap(r -> longValue(r, "id"), r -> r));
        var vector =
                analysis.dimensions().stream()
                        .map(
                                d -> {
                                    var tag = names.get(d.tagId());
                                    return new VectorDimension(
                                            Long.toString(d.tagId()),
                                            tag == null ? "" : text(tag, "code"),
                                            tag == null ? "已停用标签" : text(tag, "name"),
                                            d.rawWeight(),
                                            d.decayedWeight(),
                                            d.normalizedWeight());
                                })
                        .toList();
        var weights =
                context.tags().stream().collect(Collectors.toMap(t -> t.tagId(), t -> t.weight()));
        var ids = rows.stream().map(r -> longValue(r, "id")).toList();
        var tags =
                ids.isEmpty()
                        ? Map.<Long, List<Map<String, Object>>>of()
                        : mapper.articleTags(ids).stream()
                                .collect(Collectors.groupingBy(r -> longValue(r, "news_id")));
        var authors =
                ids.isEmpty()
                        ? Map.<Long, String>of()
                        : mapper.authors(ids).stream()
                                .collect(
                                        Collectors.toMap(
                                                r -> longValue(r, "id"), r -> text(r, "author")));
        var ranked = new ArrayList<Article>();
        for (var row : rows) {
            long id = longValue(row, "id");
            var articleTags = tags.getOrDefault(id, List.of());
            double articleWeight = articleTags.isEmpty() ? 0 : 1.0 / Math.sqrt(articleTags.size());
            var contributions =
                    articleTags.stream()
                            .map(
                                    t -> {
                                        long tagId = longValue(t, "id");
                                        double userWeight = weights.getOrDefault(tagId, 0.0);
                                        return new TagContribution(
                                                Long.toString(tagId),
                                                text(t, "name"),
                                                userWeight,
                                                articleWeight,
                                                userWeight * articleWeight);
                                    })
                            .toList();
            double similarity = number(row, "rec_similarity");
            ranked.add(
                    new Article(
                            Long.toString(id),
                            text(row, "title"),
                            authors.getOrDefault(id, "社区用户"),
                            contributions,
                            similarity,
                            60 * similarity,
                            15 * number(row, "rec_popularity"),
                            15 * number(row, "rec_freshness"),
                            10 * number(row, "rec_followed"),
                            number(row, "rec_score")));
        }
        var steps =
                List.of(
                        "读取近30天本人有效行为：阅读1、点赞3、收藏5。",
                        "按14天半衰期衰减，聚合到启用标签，保留最高64项。",
                        "L2范数为衰减权重平方和的平方根；各权重除以范数生成用户向量。",
                        "文章启用标签取1并归一化；与用户向量点积得到余弦相似度。",
                        "总分=兴趣分(60×相似度)+热度分(最高15)+新鲜度分(最高15)+关注分(0或10)，排序后取前20篇。");
        return new Report(
                new User(Long.toString(userId), text(user, "nickname")),
                context.asOf(),
                context.asOf().toLocalDate().minusDays(29),
                total,
                analysis.norm(),
                vector.isEmpty(),
                vector,
                ranked,
                steps);
    }

    private static double number(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (!(value instanceof Number number))
            throw new IllegalStateException("Missing recommendation score: " + key);
        return number.doubleValue();
    }
}
