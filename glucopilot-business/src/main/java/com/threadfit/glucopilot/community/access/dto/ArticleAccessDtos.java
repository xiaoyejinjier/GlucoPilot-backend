package com.threadfit.glucopilot.community.access.dto;

import jakarta.validation.constraints.*;

import java.util.List;

/** Community API request/response models. */
public final class ArticleAccessDtos {
    private ArticleAccessDtos() {}

    public record Scope(String visibility, List<String> viewerIds) {}

    public record Policy(String visibility, int revision, List<String> viewerIds) {}

    public record Change(
            @NotBlank String visibility,
            @Size(max = 50) List<String> viewerIds,
            @Min(0) int revision) {}

    public record Hidden(@NotNull Boolean hidden) {}
}
