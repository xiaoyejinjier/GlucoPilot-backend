package com.threadfit.glucopilot.community;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class PublicationDtos {
    private PublicationDtos() {}
    public record Tag(String code, String name, String type, int enabled) {
        public Tag(String code,String name) { this(code,name,code!=null && code.startsWith("custom_")?"USER":"OFFICIAL",1); }
        public Tag(String code,String name,int enabled) { this(code,name,code!=null && code.startsWith("custom_")?"USER":"OFFICIAL",enabled); }
    }
    public record Submit(@NotBlank @Size(max=80) String title,
                         @NotNull @Size(max=8000) String body,
                         @Size(max=3) List<String> tagCodes,
                         @Size(max=3) List<@NotBlank @Size(max=32) String> customTags,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String clientRequestId,
                         @Positive Integer revision,
                         @Size(max=6) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> imageMediaIds,
                         @Size(max=1) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> videoMediaIds,
                         @Pattern(regexp="PUBLIC|PRIVATE|FRIENDS") String visibility,
                         @Size(max=50) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> viewerIds) {
        public Submit(String title,String body,List<String> codes,List<String> custom,String key,Integer revision,List<String> images,List<String> videos) { this(title,body,codes,custom,key,revision,images,videos,null,List.of()); }
        public Submit(String title,String body,List<String> codes,List<String> custom,String key,Integer revision,List<String> images) { this(title,body,codes,custom,key,revision,images,List.of()); }
        public Submit(String title,String body,List<String> tagCodes,List<String> customTags,String clientRequestId,Integer revision) {
            this(title,body,tagCodes,customTags,clientRequestId,revision,List.of());
        }
    }
    public record ArticleImage(String mediaId) {}
    public record Review(@Positive int revision, @NotBlank String decision, @Size(max=1000) String note) {}
    public record Article(String id, String title, String summary, String body, String coverUrl,
                          List<Tag> tags, CommunityDtos.Profile author, String state, int revision,
                          String aiVerdict, String aiReason, String aiModel, String reviewNote,
                          boolean mine, boolean liked, boolean collected, long likeCount, long collectCount,
                          long commentCount, LocalDateTime createdAt, LocalDateTime publishAt, String source, List<ArticleImage> images, List<ArticleImage> videos,
                          long viewCount,String visibility,int visibilityRevision,List<String> viewerIds) {}
    public record ReportRequest(@Pattern(regexp="ARTICLE|COMMENT|USER|MESSAGE") @NotBlank String targetType,
                                @Positive long targetId,
                                @Pattern(regexp="MISINFORMATION|SPAM|ABUSE|PRIVACY|OTHER") @NotBlank String reason,
                                @Size(max=500) String details,
                                @Pattern(regexp="[A-Za-z0-9_-]{8,64}") @NotBlank String clientRequestId) {}
    public record Resolution(@NotBlank String action, @NotBlank @Size(max=1000) String note) {}
    public record AiDecision(boolean passed, String reason, String model) {}
}
