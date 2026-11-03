package com.threadfit.glucopilot.community.reaction.dto;

import lombok.Builder;
import lombok.Data;

public final class ReactionDtos {
    private ReactionDtos() {}

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
