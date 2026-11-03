package com.threadfit.glucopilot.community.recommendation.dto;

import java.util.List;

/** Explanation of the exact normalized vector passed to feed ranking. */
public record RecommendationAnalysis(
        CommunityRecommendation recommendation, double norm, List<Dimension> dimensions) {
    public RecommendationAnalysis {
        dimensions = List.copyOf(dimensions);
    }

    public record Dimension(
            long tagId, double rawWeight, double decayedWeight, double normalizedWeight) {}
}
