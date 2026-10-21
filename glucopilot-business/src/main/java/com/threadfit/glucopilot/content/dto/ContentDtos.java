package com.threadfit.glucopilot.content.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

public final class ContentDtos {

    private ContentDtos() {
    }

    @Data
    @Builder
    public static class BannerItem {
        private String id;
        private String title;
        private String subtitle;
        private String imageUrl;
        private String linkUrl;
        private Integer sortNo;
    }

    @Data
    @Builder
    public static class NewsListItem {
        private String id;
        private String title;
        private String summaryText;
        private Integer channelCode;
        private Integer contentType;
        private String coverUrl;
        private LocalDateTime publishAt;
        private Integer likeCount;
        private Integer collectCount;
    }

    @Data
    @Builder
    public static class NewsDetail {
        private String id;
        private String title;
        private String summaryText;
        private Integer channelCode;
        private Integer contentType;
        private String coverUrl;
        private String videoUrl;
        private String contentBody;
        private java.util.List<com.threadfit.glucopilot.community.PublicationDtos.ArticleImage> images;
        private java.util.List<com.threadfit.glucopilot.community.PublicationDtos.ArticleImage> videos;
        private java.util.List<com.threadfit.glucopilot.community.PublicationDtos.Tag> tags;
        private LocalDateTime publishAt;
        private Integer likeCount;
        private Integer collectCount;
        private Boolean liked;
        private Boolean collected;
        private com.threadfit.glucopilot.community.CommunityDtos.Profile author;
        private Long commentCount;
    }

    @Data
    public static class NewsActionRequest {
        /** true=点赞，false=取消；null=不改 */
        private Boolean liked;
        /** true=收藏，false=取消；null=不改 */
        private Boolean collected;
    }

    @Data
    @Builder
    public static class NewsActionResult {
        private Boolean liked;
        private Boolean collected;
        private Integer likeCount;
        private Integer collectCount;
    }
}
