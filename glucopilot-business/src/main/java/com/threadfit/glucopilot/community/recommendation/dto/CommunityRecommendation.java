package com.threadfit.glucopilot.community.recommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Normalized interests owned by the authenticated viewer, never supplied by the client. */
public record CommunityRecommendation(LocalDateTime asOf, List<TagWeight> tags, String seed) {
    public CommunityRecommendation(LocalDateTime asOf, List<TagWeight> tags) {
        this(asOf, tags, "");
    }

    public CommunityRecommendation {
        tags = List.copyOf(tags);
        seed = seed == null ? "" : seed;
    }

    public record TagWeight(long tagId, double weight) {}
}
