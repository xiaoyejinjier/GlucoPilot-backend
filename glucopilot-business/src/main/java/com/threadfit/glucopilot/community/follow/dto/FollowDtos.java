package com.threadfit.glucopilot.community.follow.dto;

import jakarta.validation.constraints.*;

public final class FollowDtos {
    private FollowDtos() {}

    public record Following(@NotNull Boolean following) {}
}
