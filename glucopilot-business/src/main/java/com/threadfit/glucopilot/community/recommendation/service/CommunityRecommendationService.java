package com.threadfit.glucopilot.community.recommendation.service;

import com.threadfit.glucopilot.community.recommendation.dto.CommunityRecommendation;
import com.threadfit.glucopilot.community.recommendation.dto.CommunityRecommendation.TagWeight;
import com.threadfit.glucopilot.community.recommendation.dto.RecommendationAnalysis;
import com.threadfit.glucopilot.community.recommendation.dto.RecommendationAnalysis.Dimension;
import com.threadfit.glucopilot.community.recommendation.entity.ArticleTagSignal;
import com.threadfit.glucopilot.community.recommendation.mapper.CommunityRecommendationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 用户兴趣向量生成：查询行为 → 时间衰减 → 标签累加 → 取前64维 → L2归一化。
 * 行为权重与文章评分SQL见resources同包根下recommendation/mapper/CommunityRecommendationMapper.xml。
 */
@Service
@RequiredArgsConstructor
public class CommunityRecommendationService {
    private final CommunityRecommendationMapper mapper;
    private static final int HISTORY_DAYS = 30; // 含今天在内的近30个自然日。
    private static final int MAX_INTEREST_TAGS = 64; // 最多保留权重最高的64个兴趣标签。
    private static final double HALF_LIFE_DAYS = 14.0; // 14天前的行为权重保留一半。

    /** 【入口A：小程序推荐】viewer为当前用户ID；只取SQL排序所需的时间和兴趣向量。 */
    public CommunityRecommendation prepare(long viewer) {
        return analyze(viewer).recommendation();
    }

    /** 【入口B：完整分析】同时返回向量、原始/衰减权重和范数，供管理端解释计算。 */
    public RecommendationAnalysis analyze(long viewer) {
        var now = LocalDateTime.now();
        // 【1. 取行为】对应XML的signals：阅读1、点赞3、收藏5；本次查询和计算使用同一个now。
        // 今天向前推29天的零点起算，按自然日限制近30天的行为窗口。
        var since = now.toLocalDate().minusDays(HISTORY_DAYS - 1).atStartOfDay();
        return analyzeSignals(mapper.signals(viewer, since, now), now);
    }

    /** 固定计算时间的入口，便于测试验证时间衰减，不依赖测试执行当天。 */
    CommunityRecommendation prepare(long viewer, LocalDateTime now) {
        var since = now.toLocalDate().minusDays(HISTORY_DAYS - 1).atStartOfDay();
        return normalize(mapper.signals(viewer, since, now), now);
    }

    /** 复用完整分析，只返回实际排序需要的推荐上下文。 */
    static CommunityRecommendation normalize(List<ArticleTagSignal> signals, LocalDateTime now) {
        return analyzeSignals(signals, now).recommendation();
    }

    /** 【核心计算】将Mapper返回的“标签ID、行为日期、行为权重”转成用户兴趣向量。 */
    static RecommendationAnalysis analyzeSignals(
            List<ArticleTagSignal> signals, LocalDateTime now) {
        Map<Long, Double> weights = new HashMap<>(); // 标签ID → 衰减后的累计权重，参与推荐。
        Map<Long, Double> rawWeights = new HashMap<>(); // 标签ID → 原始累计权重，用于解释。
        for (var signal : signals) {
            // 【2. 校验信号】忽略字段缺失、未来日期、超出窗口或非正/非有限的权重。
            if (signal.getTagId() == null
                    || signal.getSignalDate() == null
                    || signal.getWeight() == null) continue;
            long age = ChronoUnit.DAYS.between(signal.getSignalDate(), now.toLocalDate());
            if (age < 0
                    || age >= HISTORY_DAYS
                    || !Double.isFinite(signal.getWeight())
                    || signal.getWeight() <= 0) continue;
            // 【3. 时间衰减】原始权重×0.5^(天数/14)：今天保留100%，14天前保留50%。
            double decayed = signal.getWeight() * Math.pow(0.5, age / HALF_LIFE_DAYS);
            // 【4. 按标签累加】merge遇到已有标签时使用Double::sum相加。
            weights.merge(signal.getTagId(), decayed, Double::sum);
            rawWeights.merge(signal.getTagId(), signal.getWeight(), Double::sum);
        }
        // 【5. 选择维度】按衰减权重降序取前64个标签；同权重按标签ID保证稳定顺序。
        var ordered =
                weights.entrySet().stream()
                        .sorted(
                                Map.Entry.<Long, Double>comparingByValue()
                                        .reversed()
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .limit(MAX_INTEREST_TAGS)
                        .toList();
        // 【6. L2范数】各维度权重平方求和再开根号，如[6,8]的范数=sqrt(36+64)=10。
        double norm =
                Math.sqrt(ordered.stream().mapToDouble(e -> e.getValue() * e.getValue()).sum());
        // 【7. 归一化向量】每维权重除以范数：[6,8]→[0.6,0.8]；无有效兴趣则返回空向量。
        // TagWeight保存标签ID和向量值；SQL通过标签ID匹配文章，计算余弦相似度。
        var tags =
                norm == 0
                        ? List.<TagWeight>of()
                        : ordered.stream()
                                .map(e -> new TagWeight(e.getKey(), e.getValue() / norm))
                                .toList();
        // 【8. 解释数据】为每个保留标签返回原始权重、衰减权重、归一化向量值。
        var dimensions =
                tags.stream()
                        .map(
                                tag ->
                                        new Dimension(
                                                tag.tagId(),
                                                rawWeights.get(tag.tagId()),
                                                weights.get(tag.tagId()),
                                                tag.weight()))
                        .toList();
        // recommendation用于实际排序；norm和dimensions用于管理端展示计算过程。
        return new RecommendationAnalysis(new CommunityRecommendation(now, tags), norm, dimensions);
    }
}
