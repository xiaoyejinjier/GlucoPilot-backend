package com.threadfit.glucopilot.community.draft.dto;

import com.threadfit.glucopilot.community.article.dto.PublicationDtos;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

/** Community API request/response models. */
public final class CommunityDraftDtos {
    private CommunityDraftDtos() {}

    public record Payload(
            String title,
            String body,
            List<String> tagCodes,
            List<String> customTags,
            List<String> imageMediaIds,
            String clientRequestId,
            Integer revision,
            Long sourceArticleId,
            Integer sourceRevision,
            List<String> videoMediaIds,
            String visibility,
            List<String> viewerIds) {
        public Payload(
                String title,
                String body,
                List<String> codes,
                List<String> custom,
                List<String> images,
                String key,
                Integer revision,
                Long source,
                Integer sourceRevision,
                List<String> videos) {
            this(
                    title,
                    body,
                    codes,
                    custom,
                    images,
                    key,
                    revision,
                    source,
                    sourceRevision,
                    videos,
                    null,
                    List.of());
        }

        public Payload(
                String title,
                String body,
                List<String> codes,
                List<String> custom,
                List<String> images,
                String key,
                Integer revision,
                Long source,
                Integer sourceRevision) {
            this(
                    title,
                    body,
                    codes,
                    custom,
                    images,
                    key,
                    revision,
                    source,
                    sourceRevision,
                    List.of());
        }
    }

    public record Publish(int revision, String clientRequestId) {}

    public record Draft(
            String id,
            String title,
            String body,
            List<String> tagCodes,
            List<String> customTags,
            List<PublicationDtos.ArticleImage> images,
            int revision,
            String sourceArticleId,
            Integer sourceRevision,
            LocalDateTime updatedAt,
            List<PublicationDtos.ArticleImage> videos,
            String visibility,
            List<String> viewerIds) {}
}
