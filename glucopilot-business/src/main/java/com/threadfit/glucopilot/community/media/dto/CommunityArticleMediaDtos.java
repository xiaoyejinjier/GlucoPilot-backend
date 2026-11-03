package com.threadfit.glucopilot.community.media.dto;

import jakarta.validation.constraints.*;

/** Community API request/response models. */
public final class CommunityArticleMediaDtos {
    private CommunityArticleMediaDtos() {}

    public record Image(byte[] bytes, String mimeType) {}
}
