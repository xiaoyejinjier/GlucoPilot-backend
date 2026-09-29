package com.threadfit.glucopilot.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

public final class FileDtos {

    private FileDtos() {
    }

    /** 凭证接口入参：{ bizType, mimeType, sizeBytes }。 */
    @Data
    public static class UploadCredentialRequest {
        /** 业务类型（CodeEnum.MediaBizType）。 */
        @NotNull
        private Integer bizType;

        /** MIME 类型。 */
        @NotBlank
        private String mimeType;

        /** 字节数。 */
        @NotNull
        @Positive
        private Long sizeBytes;
    }

    /** 登记接口入参：{ bizType, storageKey, mimeType, sizeBytes, sha256Hex }。 */
    @Data
    public static class RegisterRequest {
        /** 业务类型（CodeEnum.MediaBizType）。 */
        @NotNull
        private Integer bizType;

        /** 直传对象 Key。 */
        @NotBlank
        private String storageKey;

        /** MIME 类型。 */
        @NotBlank
        private String mimeType;

        /** 字节数。 */
        @NotNull
        @Positive
        private Long sizeBytes;

        /** 内容哈希（可选，用于去重）。 */
        private String sha256Hex;
    }
}