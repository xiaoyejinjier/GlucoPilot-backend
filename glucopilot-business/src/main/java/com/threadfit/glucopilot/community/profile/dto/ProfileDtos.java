package com.threadfit.glucopilot.community.profile.dto;

import jakarta.validation.constraints.*;

public final class ProfileDtos {
    private ProfileDtos() {}

    public record Profile(
            String key,
            String userId,
            String nickname,
            String avatarUrl,
            String bio,
            boolean followed,
            boolean isSelf) {}
}
