package com.threadfit.glucopilot.content.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 内容媒体（资讯/轮播）相关请求与响应 DTO。 */
public final class CmsMediaDtos {

    private CmsMediaDtos() {
    }

    /** 申请上传凭证。 */
    @Data
    public static class UploadCredentialRequest {
        /** 素材用途：news（资讯配图）或 banner（轮播图）。 */
        @NotBlank
        private String purpose;

        /** 业务类型，内容媒体固定为 CMS(5)。 */
        @NotNull
        private Integer bizType;

        /** MIME，仅允许 image/jpeg、image/png。 */
        @NotBlank
        private String mimeType;

        /** 声明的字节数，登记时与真实对象大小核对。 */
        @NotNull
        @Positive
        private Long sizeBytes;
    }

    /** 直传完成后的登记请求。 */
    @Data
    public static class RegisterRequest {
        /** 素材用途：news 或 banner。 */
        @NotBlank
        private String purpose;

        /** 业务类型，内容媒体固定为 CMS(5)。 */
        @NotNull
        private Integer bizType;

        /** 直传落点的对象 Key，服务端会校验归属当前运营账号。 */
        @NotBlank
        private String storageKey;

        @NotBlank
        private String mimeType;

        @NotNull
        @Positive
        private Long sizeBytes;
    }

    /** 管理端列表/登记返回的素材条目。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaItem {
        private String id;
        /** news / banner */
        private String purpose;
        /** 稳定相对路径，如 /api/v1/cms/images/{id} */
        private String fileUrl;
        private String mimeType;
        private Long sizeBytes;
        private Integer widthPx;
        private Integer heightPx;
        /** 展示用上传人，如「运营#9」 */
        private String uploader;
        private String createdAt;
    }
}
