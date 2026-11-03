package com.threadfit.glucopilot.community.recommendation.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AdminRecommendationDtos {
    private AdminRecommendationDtos() {}

    public record User(String id, String nickname) {}

    public record VectorDimension(
            String tagId,
            String code,
            String name,
            double rawWeight,
            double decayedWeight,
            double normalizedWeight) {}

    public record TagContribution(
            String tagId,
            String name,
            double userWeight,
            double articleWeight,
            double contribution) {}

    public record Article(
            String id,
            String title,
            String author,
            List<TagContribution> tags,
            double similarity,
            double interestScore,
            double popularityScore,
            double freshnessScore,
            double followingScore,
            double totalScore) {}

    public record Report(
            User user,
            LocalDateTime generatedAt,
            LocalDate historyStart,
            long candidateCount,
            double vectorNorm,
            boolean coldStart,
            List<VectorDimension> vector,
            List<Article> articles,
            List<String> steps) {}
}
