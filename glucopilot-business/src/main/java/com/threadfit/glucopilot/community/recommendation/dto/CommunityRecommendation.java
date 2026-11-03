package com.threadfit.glucopilot.community.recommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Normalized interests owned by the authenticated viewer, never supplied by the client. */
public record CommunityRecommendation(LocalDateTime asOf, List<TagWeight> tags) {
    public CommunityRecommendation {
        tags = List.copyOf(tags);
    }

    public record TagWeight(long tagId, double weight) {}
}
