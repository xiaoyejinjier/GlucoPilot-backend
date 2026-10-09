package com.threadfit.glucopilot.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

public final class FileDtos {

    private FileDtos() {
    }

    /**
     * 凭证接口入参：{ bizType, mimeType, sizeBytes }。
     * 校验顺序约定：bizType ∈ MediaBizType → size ≤20MB → MIME 白名单（由 Service 统一执行）。
     */
    @Data
    public static class UploadCredentialRequest {
        /** 业务类型（CodeEnum.MediaBizType）。 */
        @NotNull
        private Integer bizType;

        /** MIME 类型（须在按 bizType 的白名单内）。 */
        @NotBlank
        private String mimeType;

        /** 字节数（必填且为正，服务端再限额 20MB）。 */
        @NotNull
        @Positive
        private Long sizeBytes;
    }

    /**
     * 登记接口入参：{ bizType, storageKey, mimeType, sizeBytes, sha256Hex }。
     * storageKey 由客户端回传，服务端必须重新校验其前缀含当前 userId，防止伪造/越权。
     */
    @Data
    public static class RegisterRequest {
        /** 业务类型（CodeEnum.MediaBizType）。 */
        @NotNull
        private Integer bizType;

        /** 直传对象 Key（客户端回传，服务端仍需做所有权校验）。 */
        @NotBlank
        private String storageKey;

        /** MIME 类型。 */
        @NotBlank
        private String mimeType;

        /** 字节数。 */
        @NotNull
        @Positive
        private Long sizeBytes;

        /** 内容哈希（可选，用于去重；缺失则跳过内容级去重）。 */
        private String sha256Hex;
    }
}