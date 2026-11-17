package com.threadfit.glucopilot.community.recommendation.dto;

import jakarta.validation.constraints.*;

import java.util.List;

public record RecommendationExposure(
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Positive Long> newsIds) {}
