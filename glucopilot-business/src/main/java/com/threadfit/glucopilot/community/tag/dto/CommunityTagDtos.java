package com.threadfit.glucopilot.community.tag.dto;

import jakarta.validation.constraints.*;

/** Community API request/response models. */
public final class CommunityTagDtos {
    private CommunityTagDtos() {}

    public record Change(
            @NotBlank @Size(max = 32) String name,
            @Min(0) @Max(9999) int sortNo,
            @Min(0) @Max(1) int enabled) {}

    public record Row(
            String id,
            String code,
            String name,
            String type,
            int sortNo,
            int enabled,
            long articleCount) {}
}
